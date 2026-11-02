#!/usr/bin/env python3
"""Build web/data.json for the web page, which cannot call CWA itself (its host blocks fetch).

The key comes from web/.cwa_key or $CWA_API_KEY and is never written to data.json;
error text must never include a request URL, which carries it.

  python3 web/snapshot.py            all coastal towns, needs the key
  python3 web/snapshot.py --sample   Keelung only, from the API responses in web/fixtures/
"""
import datetime
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TOWNS = os.path.join(HERE, "coast_towns.tsv")
FIXTURES = os.path.join(HERE, "fixtures")
OUTPUT = os.path.join(HERE, "data.json")
TAIPEI = datetime.timezone(datetime.timedelta(hours=8))
TIDE_DAYS = 30
SHAPE_ERROR = "資料格式與預期不符，請回報此資料集"


class ApiError(Exception):
    pass


class TransientError(Exception):
    """A failure worth retrying; read() turns it into ApiError once retries run out."""


def ci(obj, name):
    """CWA mixes camelCase and PascalCase between versions, so keys match ignoring case."""
    for key, value in obj.items():
        if key.lower() == name.lower():
            return value
    return None


def text(obj, name):
    value = ci(obj, name)
    return "" if value is None or isinstance(value, (dict, list)) else str(value).strip()


def items(value):
    if isinstance(value, list):
        return [v for v in value if isinstance(v, dict)]
    return [value] if isinstance(value, dict) else []


def objects_with(value, key):
    """Every object at or below value that has key; does not descend into a match."""
    found = []
    if isinstance(value, dict):
        if ci(value, key) is not None:
            return [value]
        for child in value.values():
            found += objects_with(child, key)
    elif isinstance(value, list):
        for child in value:
            found += objects_with(child, key)
    return found


def root(body):
    try:
        parsed = json.loads(body)
    except ValueError:
        raise ApiError(SHAPE_ERROR)
    if not isinstance(parsed, dict):
        raise ApiError(SHAPE_ERROR)
    if text(parsed, "success").lower() == "false":
        raise ApiError(text(parsed, "message") or "氣象署回傳失敗")
    return parsed


def usable(value):
    """Blank, "None", "-" and the -99-style sentinels all mean no value."""
    if not value or value.lower() in ("none", "null", "x") or set(value) == {"-"}:
        return False
    try:
        return float(value) > -99
    except ValueError:
        return True


def element_value(series, element, field, time_key):
    entries = items(ci(series.get(element, {}).get(time_key, {}), "ElementValue"))
    value = text(entries[0], field) if entries else ""
    return value if usable(value) else ""


def parse_location(location):
    """F-D0047 elements use different cadences, so all join on the weather period's start."""
    series = {}
    for element in items(ci(location, "WeatherElement")):
        series[text(element, "ElementName")] = {
            (text(t, "StartTime") or text(t, "DataTime")): t for t in items(ci(element, "Time"))
        }
    base = series.get("天氣現象")
    if not base:
        raise ApiError(SHAPE_ERROR)
    periods = []
    for start in sorted(base):
        value = lambda element, field: element_value(series, element, field, start)
        periods.append({
            "start": start,
            "end": text(base[start], "EndTime"),
            "wx": value("天氣現象", "Weather"),
            "pop": value("3小時降雨機率", "ProbabilityOfPrecipitation"),
            "temp": value("溫度", "Temperature"),
            "feels": value("體感溫度", "ApparentTemperature"),
            "humidity": value("相對濕度", "RelativeHumidity"),
            "windSpeed": value("風速", "WindSpeed"),
            "beaufort": value("風速", "BeaufortScale"),
            "windDir": value("風向", "WindDirection"),
            "wave": "", "waveDir": "", "wavePeriod": "", "currentSpeed": "", "currentDir": "",
        })
    return periods


def parse_townships(body):
    return {text(loc, "LocationName"): parse_location(loc)
            for loc in objects_with(root(body), "WeatherElement")}


def parse_coast(body):
    points = {}
    for point in objects_with(root(body), "LocationName"):
        series = {}
        for element in items(ci(point, "WeatherElement")):
            series[text(element, "ElementName")] = {text(t, "DataTime"): t for t in items(ci(element, "Time"))}
        slots = {}
        for t in sorted(series.get("浪高", {})):
            value = lambda element, field: element_value(series, element, field, t)
            slots[t] = {
                "windSpeed": value("風速", "WindSpeed"),
                "beaufort": value("風速", "BeaufortScale"),
                "windDir": value("風向", "WindDirection"),
                "wave": value("浪高", "WaveHeight"),
                "waveDir": value("浪向", "WaveDirection"),
                "wavePeriod": value("浪週期", "WavePeriod"),
                "currentSpeed": value("流速", "OceanCurrentSpeed"),
                "currentDir": value("流向", "OceanCurrentDirection"),
            }
        points[text(point, "LocationName")] = slots
    return points


def parse_tide(body):
    """High/low waters as [time, high, cm], and each day's [TideRange (大/中/小), LunarDate] by date."""
    marks, days = [], {}
    for day in objects_with(root(body), "Date"):
        if text(day, "TideRange") or text(day, "LunarDate"):
            days[text(day, "Date")] = [text(day, "TideRange"), text(day, "LunarDate")]
        for event in objects_with(ci(day, "Time"), "DateTime"):
            heights = ci(event, "TideHeights")
            height = text(heights, "AboveLocalMSL") if isinstance(heights, dict) else ""
            try:
                marks.append([text(event, "DateTime"), 1 if text(event, "Tide") == "滿潮" else 0, int(height)])
            except ValueError:
                pass
    return sorted(marks), days


def with_sea(periods, slots):
    """Sea wind replaces land wind; a period the coastal file misses goes blank, not land wind."""
    if not slots:
        return periods
    merged = []
    for period in periods:
        blank = {"windSpeed": "", "beaufort": "", "windDir": ""}
        merged.append({**period, **slots.get(period["start"], blank)})
    return merged


FIELDS = ("start", "end", "wx", "pop", "temp", "feels", "humidity", "windSpeed", "beaufort", "windDir",
          "wave", "waveDir", "wavePeriod", "currentSpeed", "currentDir")


def place_entry(periods, tide, slots, notes):
    marks, days = tide
    return {
        "p": [[p[f] for f in FIELDS] for p in with_sea(periods, slots)],
        "t": marks,
        "d": days,
        "sea": bool(slots),
        "notes": notes,
    }


def read_once(url):
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            raise ApiError("授權碼不正確")
        if e.code == 429:
            raise ApiError("查詢次數已達上限，請稍後再試")
        if e.code >= 500:
            raise TransientError("氣象署伺服器回應異常（%d）" % e.code)
        raise ApiError("氣象署伺服器回應異常（%d）" % e.code)
    except (urllib.error.URLError, OSError, http.client.HTTPException):
        # IncompleteRead (a dropped connection mid-body) is an HTTPException, not an OSError.
        raise TransientError("無法連線，請確認網路")


def read(url, attempts=3):
    """Retries dropped connections and 5xx; a bad key or the rate limit fails at once."""
    for attempt in range(attempts):
        try:
            return read_once(url)
        except TransientError as e:
            if attempt == attempts - 1:
                raise ApiError(str(e))
            time.sleep(5 * (attempt + 1))


def datastore(key, dataset, params):
    query = urllib.parse.urlencode({**params, "Authorization": key, "format": "JSON"})
    return read("https://opendata.cwa.gov.tw/api/v1/rest/datastore/%s?%s" % (dataset, query))


def towns():
    with open(TOWNS, encoding="utf-8") as f:
        rows = [line.rstrip("\n").split("\t") for line in f if line.strip()]
    result = []
    for county, name, dataset, tide_id, point in rows:
        # Islands have a north and a south coastal point, listed as separate places.
        place = "%s %s" % (name, point.split("_")[1].replace("沿海", "")) if "_" in point else name
        result.append({"county": county, "name": name, "dataset": dataset, "tide": tide_id,
                       "point": point, "place": place, "label": county + place})
    return result


def api_key():
    key = os.environ.get("CWA_API_KEY", "")
    path = os.path.join(HERE, ".cwa_key")
    if not key and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            key = f.read().strip()
    if not key:
        sys.exit("找不到授權碼：請把授權碼存到 web/.cwa_key，或設定 CWA_API_KEY")
    return key


def live():
    key = api_key()
    all_towns = towns()
    errors = 0
    forecasts, forecast_errors = {}, {}
    for dataset in sorted({t["dataset"] for t in all_towns}):
        try:
            forecasts[dataset] = parse_townships(datastore(key, dataset, {}))
        except ApiError as e:
            forecast_errors[dataset] = str(e)
            errors += 1
    tides, tide_errors = {}, {}
    today = datetime.datetime.now(TAIPEI).replace(hour=0, minute=0, second=0, microsecond=0, tzinfo=None)
    window = {"timeFrom": today.isoformat(), "timeTo": (today + datetime.timedelta(days=TIDE_DAYS)).isoformat()}
    for tide_id in sorted({t["tide"] for t in all_towns}):
        try:
            tides[tide_id] = parse_tide(datastore(key, "F-A0021-001", {"LocationId": tide_id, **window}))
        except ApiError as e:
            tide_errors[tide_id] = str(e)
            errors += 1
        time.sleep(0.15)
    coast_note = None
    points = {}
    try:
        query = urllib.parse.urlencode({"Authorization": key, "downloadType": "WEB", "format": "JSON"})
        points = parse_coast(read("https://opendata.cwa.gov.tw/fileapi/v1/opendataapi/F-D0047-095?" + query))
    except ApiError as e:
        coast_note = "沿海預報載入失敗：%s" % e
        errors += 1
    places = {}
    for town in all_towns:
        if town["dataset"] in forecast_errors:
            places[town["label"]] = {"error": forecast_errors[town["dataset"]]}
            continue
        periods = forecasts[town["dataset"]].get(town["name"])
        if periods is None:
            places[town["label"]] = {"error": "查無此鄉鎮的預報"}
            errors += 1
            continue
        notes = [coast_note] if coast_note else []
        if town["tide"] in tide_errors:
            notes.append("潮汐載入失敗：%s" % tide_errors[town["tide"]])
        places[town["label"]] = place_entry(periods, tides.get(town["tide"], ([], {})), points.get(town["point"], {}), notes)
    if len(forecast_errors) == len(forecasts) + len(forecast_errors):
        sys.exit("所有鄉鎮預報都失敗：%s" % next(iter(forecast_errors.values())))
    return places, errors


def sample():
    def fixture(name):
        with open(os.path.join(FIXTURES, name), encoding="utf-8") as f:
            return f.read()
    periods = parse_townships(fixture("township.json"))["中正區"]
    slots = parse_coast(fixture("coast.json"))["中正區沿海"]
    return {"基隆市中正區": place_entry(periods, parse_tide(fixture("tide.json")), slots, [])}, 0


def main():
    is_sample = "--sample" in sys.argv[1:]
    places, errors = sample() if is_sample else live()
    data = {
        "generated": "2026-11-01T16:49:47+08:00" if is_sample else datetime.datetime.now(TAIPEI).isoformat(timespec="seconds"),
        "sample": is_sample,
        "fields": FIELDS,
        "places": places,
    }
    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print("%d 個地點，%d 項錯誤，%d KB" % (len(places), errors, os.path.getsize(OUTPUT) // 1024))


if __name__ == "__main__":
    main()
