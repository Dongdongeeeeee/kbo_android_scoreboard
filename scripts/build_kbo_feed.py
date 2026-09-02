import json
import os
import urllib.parse
import urllib.request
from urllib.error import HTTPError, URLError
from datetime import date, timedelta

BASE = "https://www.koreabaseball.com/ws"
HEADERS = {
    "User-Agent": "Mozilla/5.0",
    "Accept": "application/json, text/javascript, */*; q=0.01",
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    "X-Requested-With": "XMLHttpRequest",
    "Origin": "https://www.koreabaseball.com",
}


def post(path, fields):
    body = urllib.parse.urlencode(fields).encode()
    request = urllib.request.Request(BASE + path, body, HEADERS, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            text = response.read().decode("utf-8-sig").strip()
    except (HTTPError, URLError, TimeoutError):
        return None
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return None


def unwrap(value):
    if isinstance(value, str):
        try:
            return json.loads(value.lstrip("\ufeff"))
        except json.JSONDecodeError:
            return value
    return value


def text(obj, *keys):
    for key in keys:
        value = obj.get(key)
        if value not in (None, ""):
            return str(value).strip()
    return ""


def games_for(day):
    root = post("/Main.asmx/GetKboGameList", {
        "leId": "1", "srId": "0,1,3,4,5,6,7,9",
        "date": day.strftime("%Y%m%d"),
    })
    if root is None:
        return []
    root = unwrap(root)
    if isinstance(root, dict):
        for key in ("d", "game", "Data", "data", "result"):
            if isinstance(root.get(key), list):
                root = root[key]
                break
    return root if isinstance(root, list) else []


def lineup(game_id):
    root = post("/Schedule.asmx/GetLineUpAnalysis", {
        "leId": "1", "srId": "0", "seasonId": game_id[:4], "gameId": game_id,
    })
    if root is None:
        return {"away": [], "home": []}
    root = unwrap(root)
    if not isinstance(root, list):
        return {"away": [], "home": []}

    def rows(index):
        payload = unwrap(root[index]) if len(root) > index else {}
        result = []
        for item in payload.get("rows", []) if isinstance(payload, dict) else []:
            cells = [str(cell.get("Text", "")).strip() for cell in item.get("row", [])]
            if len(cells) >= 3 and cells[0].isdigit():
                result.append({"order": cells[0], "position": cells[1], "name": cells[2]})
        return result

    return {"away": rows(4), "home": rows(3)}


def main():
    today = date.today()
    output = []
    failed_days = []
    for offset in range(-14, 8):
        day = today + timedelta(days=offset)
        games = games_for(day)
        if not games:
            failed_days.append(day.isoformat())
        for obj in games:
            game_id = text(obj, "G_ID", "GAME_ID", "gameId")
            away = text(obj, "T_ID", "AWAY_ID", "AWAY_TEAM_ID")
            home = text(obj, "B_ID", "HOME_ID", "HOME_TEAM_ID")
            if not game_id or not away or not home:
                continue
            game = {
                "gameId": game_id,
                "date": day.isoformat(),
                "away": away,
                "home": home,
                "time": text(obj, "G_DT", "GAME_TIME", "time"),
                "venue": text(obj, "STADIUM_NM", "STADIUM", "stadium"),
                "lineup": lineup(game_id),
            }
            output.append(game)
    os.makedirs("site/data", exist_ok=True)
    with open("site/data/games.json", "w", encoding="utf-8") as file:
        json.dump({
            "updatedAt": date.today().isoformat(),
            "games": output,
            "sourceStatus": "partial" if failed_days else "ok",
            "unavailableDates": failed_days,
        }, file, ensure_ascii=False)


if __name__ == "__main__":
    main()
