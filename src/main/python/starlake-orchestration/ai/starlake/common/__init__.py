import re
from datetime import datetime, timedelta
from typing import Union

def keep_ascii_only(text):
    return re.sub(r'[^\x00-\x7F]+', '_', text)

def sanitize_id(id: str):
    return keep_ascii_only(re.sub("[^a-zA-Z0-9\-_]", "_", id.replace("$", "S")))

class MissingEnvironmentVariable(Exception):
    pass

TODAY = datetime.today().strftime('%Y-%m-%d')

def asQueryParameters(parameters: Union[dict,None]=None) -> str:
    from urllib.parse import quote
    if parameters is None:
        parameters = dict()
    if parameters.__len__() > 0:
        return '?' + '&'.join(list(f'{quote(k)}={quote(v)}' for (k, v) in parameters.items()))
    else:
        return ''

def cron_start_time() -> datetime:
    return datetime.fromtimestamp(datetime.now().timestamp())

sl_schedule_format = '%Y%m%dT%H%M'

def sl_schedule(cron: str, start_time: datetime = cron_start_time(), format: str = sl_schedule_format) -> str:
    from croniter import croniter
    return croniter(cron, start_time).get_prev(datetime).strftime(format)

def get_cron_frequency(cron_expression, start_time: datetime, period='day'):
    """
    Calculate the frequency of a cron expression within a specific time period.

    :param cron_expression: A string representing the cron expression.
    :param start_time: The starting datetime to evaluate from.
    :param period: The time period ('day', 'week', 'month') over which to calculate frequency.
    :return: The frequency of runs in the given period.
    """
    from croniter import croniter
    iter = croniter(cron_expression, start_time)
    end_time = start_time

    if period == 'day':
        end_time += timedelta(days=1)
    elif period == 'week':
        end_time += timedelta(weeks=1)
    elif period == 'month':
        end_time += timedelta(days=30)  # Approximate a month
    else:
        raise ValueError("Unsupported period. Choose from 'day', 'week', 'month'.")

    frequency = 0
    while True:
        next_run = iter.get_next(datetime)
        if next_run < end_time:
            frequency += 1
        else:
            break
    return frequency

def sort_crons_by_frequency(cron_expressions, period='day'):
    """
    Sort cron expressions by their frequency.

    :param cron_expressions: A list of cron expressions.
    :param period: The period over which to calculate frequency ('day', 'week', 'month').
    :return: A sorted list of cron expressions by frequency (most frequent first).
    """
    start_time = cron_start_time()
    frequencies = [(expr, get_cron_frequency(expr, start_time, period)) for expr in cron_expressions]
    # Sort by frequency in descending order
    sorted_expressions = sorted(frequencies, key=lambda x: x[1], reverse=True)
    return sorted_expressions
