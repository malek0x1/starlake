package ai.starlake.lineage

import ai.starlake.config.Settings
import ai.starlake.lineage.ColLineage.{Column, Relation, Table}
import ai.starlake.schema.handlers.{SchemaHandler, StorageHandler}
import ai.starlake.sql.SQLUtils
import ai.starlake.transpiler.JSQLColumResolver
import ai.starlake.transpiler.schema.{JdbcColumn, JdbcMetaData, JdbcResultSetMetaData}
import ai.starlake.utils.{JsonSerializer, Utils}
import com.typesafe.scalalogging.StrictLogging

import scala.jdk.CollectionConverters._

class ColLineage(
  settings: Settings,
  schemaHandler: SchemaHandler,
  storageHandler: StorageHandler
) extends StrictLogging {
  def run1() = {
    val query =
      """
      |with mycte as (
      |  select o.amount, c.id, CURRENT_TIMESTAMP() as timestamp1, o.amount as amount2
      |  from `sales.orders` o, `sales.customers` c
      |  where o.customer_id = c.id
      |),
      |yourcte as (select * from mycte)
      |select id, sumx(sumy(mycte.amount + yourcte.amount) + yourcte.amount) as sum, timestamp1, amount as amount2, yourcte.amount as amount3
      |from mycte, yourcte
      |group by yourcte.id, yourcte.timestamp1
      |""".stripMargin
    println(query)
    val metaData: JdbcMetaData = new JdbcMetaData("", "")
      .addTable(
        "sales",
        "orders",
        new JdbcColumn("customer_id"),
        new JdbcColumn("order_id"),
        new JdbcColumn("amount"),
        new JdbcColumn("seller_id")
      )
      .addTable(
        "sales",
        "customers",
        new JdbcColumn("id"),
        new JdbcColumn("signup"),
        new JdbcColumn("contact"),
        new JdbcColumn("birthdate"),
        new JdbcColumn("name1"),
        new JdbcColumn("name2"),
        new JdbcColumn("id1")
      )

    val res: JdbcResultSetMetaData =
      JSQLColumResolver
        .getResultSetMetaData(query, JdbcMetaData.copyOf(metaData))
        .asInstanceOf[JdbcResultSetMetaData]

    extractTables(res).foreach(println)
    extractRelations("mySchema", "myTable", res).foreach(println)
  }

  val domains = schemaHandler.domains()
  val tasks = schemaHandler.tasks()

  def getTableWithColumnNames(domain: String, table: String): Table = {
    domains
      .find(_.name.equalsIgnoreCase(domain))
      .flatMap { domain =>
        domain.tables.find(_.finalName.equalsIgnoreCase(table)).map { table =>
          Table(domain.name, table.name, table.attributes.map(_.getFinalName()), isTask = false)
        }
      }
      .getOrElse(Table(domain, table, Nil, isTask = false))
  }

  def extractTables(column: JdbcColumn): List[Table] = {
    val thisTable = getTableWithColumnNames(column.tableSchema, column.tableName)

    val scopeTable = getTableWithColumnNames(column.scopeSchema, column.scopeTable)
    val childTables = column.getChildren.asScala.flatMap { child =>
      extractTables(child)
    }.toList
    val alTables = thisTable :: scopeTable :: childTables
    alTables.filter(it => it.table != null && it.table.nonEmpty)
  }
  def extractTables(resultSetMetaData: JdbcResultSetMetaData): List[Table] = {
    val allTables = resultSetMetaData.getColumns.asScala
      .flatMap { column =>
        extractTables(column)
      }
      .toList
      .distinct
    val alltaskNames = schemaHandler.taskNames().map(_.toLowerCase)
    allTables.map { table =>
      val isTask = alltaskNames.contains(table.fullName.toLowerCase)
      table.copy(isTask = isTask)
    }
  }

  def extractRelations(
    schemaName: String,
    tableName: String,
    columnName: String,
    expression: Option[String],
    column: JdbcColumn
  ): List[Relation] = {
    val colName = Option(columnName).getOrElse(column.columnName)
    val currentColumn = Column(schemaName, tableName, colName)
    val relations =
      if (
        Option(column.tableName).isDefined &&
        column.tableName.nonEmpty &&
        Option(column.columnName).isDefined &&
        column.columnName.nonEmpty
      ) { // this is a not a function
        val parentColumn = Column(column.tableSchema, column.tableName, column.columnName)
        val immediateRelations =
          if (Option(column.scopeTable).isDefined && column.scopeTable.nonEmpty) {
            val scopeColumn = Column(column.scopeSchema, column.scopeTable, column.columnName)
            if (scopeColumn == parentColumn) {
              List(Relation(parentColumn, currentColumn, expression))
            } else {
              List(
                Relation(parentColumn, currentColumn, expression),
                Relation(scopeColumn, parentColumn, expression)
              )
            }
          } else {
            List(Relation(parentColumn, currentColumn, expression))
          }
        val relations =
          column.getChildren.asScala.flatMap { child =>
            if (
              Option(child.tableName).isEmpty ||
              child.tableName.isEmpty ||
              Option(child.columnName).isEmpty ||
              child.columnName.isEmpty
            ) {
              Nil
            } else {
              extractRelations(
                column.tableSchema,
                column.tableName,
                column.columnName,
                Option(column.getExpression).map(_.toString),
                child
              )
            }
          }.toList

        immediateRelations ++ relations
      } else if (
        (Option(column.tableName).isEmpty || column.tableName.isEmpty) &&
        (Option(column.columnName).isDefined && column.columnName.nonEmpty)
      ) { // this is a function
        val relations =
          column.getChildren.asScala.flatMap { child =>
            extractRelations(
              schemaName,
              tableName,
              colName,
              Option(column.getExpression).map(_.toString),
              child
            )
          }.toList
        relations
      } else {
        Nil
      }
    relations
  }
  def extractRelations(
    schemaName: String,
    tableName: String,
    resultSetMetaData: JdbcResultSetMetaData
  ): List[Relation] = {
    val columns = resultSetMetaData.getColumns.asScala
    val labels = resultSetMetaData.getLabels.asScala
    assert(columns.size == labels.size)
    val relations =
      columns
        .zip(labels)
        .flatMap { case (column, label) =>
          val columnName = if (Option(label).isEmpty) column.columnName else label
          extractRelations(
            schemaName,
            tableName,
            columnName,
            Option(column.getExpression).map(_.toString),
            column
          )
        }
        .toList
    relations.distinct
  }
  def colLineage(colLineageConfig: ColLineageConfig): Option[ColLineage.Lineage] = {
    val taskDesc = schemaHandler.taskOnly(colLineageConfig.task, reload = true)
    taskDesc.toOption.map { task =>
      val sqlSubst = task.sql
        .map { sql =>
          schemaHandler.substituteRefTaskMainSQL(sql, task.getRunConnection()(settings))
        }
        .getOrElse("")
      val sql = sqlSubst.replaceAll("::[a-zA-Z0-9]+", "")
      // remove all ::TEXT (type change in columns)
      val tableNames = SQLUtils.extractTableNames(sql)
      val quoteFreeTables = tableNames.map(SQLUtils.quoteFreeTableName)
      val tablesWithColumnNames = schemaHandler.getTablesWithColumnNames(quoteFreeTables)
      var jdbcMetadata = new JdbcMetaData("", "")
      tablesWithColumnNames.foreach { case (domainName, table) =>
        val jdbcColumns = table.attrs.map { attrName => new JdbcColumn(attrName) }
        jdbcMetadata = jdbcMetadata.addTable("", domainName, table.name, jdbcColumns.asJava)
      }

      val res: JdbcResultSetMetaData =
        JSQLColumResolver
          .getResultSetMetaData(
            sql,
            JdbcMetaData.copyOf(jdbcMetadata.setErrorMode(JdbcMetaData.ErrorMode.LENIENT))
          )

      val tables = extractTables(res)
      val relations = extractRelations(task.domain, task.table, res)
      println("relations")
      relations.foreach(println)

      println("tablesInRelations(relations)")
      tablesInRelations(relations).foreach(println)

      val allTables = tablesInRelations(relations) ++ tables
      println("allTables")
      allTables.foreach(println)

      val finalTables = allTables
        .groupBy(t => (t.domain, t.table))
        .map { case ((domainName, tableName), table) =>
          Table(
            domainName,
            tableName,
            table.flatMap(_.columns).distinct,
            isTask = table.exists(_.isTask)
          )
        }
        .toList
      finalTables.foreach(println)

      val lineage = ColLineage.Lineage(finalTables, relations)
      val diagramAsStr =
        JsonSerializer.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(lineage)
      Utils.save(colLineageConfig.outputFile, diagramAsStr)
      lineage
    }
  }

  def tablesInRelations(relations: List[Relation]): List[Table] = {
    val tables =
      relations.flatMap { relation =>
        val table1 =
          Table(
            relation.from.domain,
            relation.from.table,
            List(relation.from.column),
            isTask = false
          )
        val table2 =
          Table(relation.to.domain, relation.to.table, List(relation.to.column), isTask = false)
        List(table1, table2)
      }
    tables
  }

}

object ColLineage {
  case class Column(domain: String, table: String, column: String)
  case class Relation(from: Column, to: Column, expression: Option[String])
  case class Table(domain: String, table: String, columns: List[String], isTask: Boolean) {
    def fullName: String = s"$domain.$table"
  }
  case class Lineage(tables: List[Table], relations: List[Relation])
}
