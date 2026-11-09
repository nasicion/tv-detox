#!/usr/bin/env python3
"""Downloads nature photos for the weather wallpaper and copies them to your TV.

  PIXABAY_KEY=your-key python3 fetch-wallpapers.py <TV_IP>

One photo for each part of the day (morning, noon, afternoon, night) and each kind of weather
(clear, cloudy, rain, snow, fog): 20 in all, mountains and woods. They are saved in out/wallpapers
and pushed with adb to the home screen's own folder on the TV. The key stays on this computer; the
TV never talks to Pixabay. Photos: pixabay.com, credited on the home screen.

Free key: sign up at https://pixabay.com, then it is shown on https://pixabay.com/api/docs/
The normal key gives photos up to 1280 px wide; with full API access (asked for on that page) they
are 1920 px. Needs only python3 and adb. Run it again to pick new photos.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SEARCH = {
    "morning": {
        "clear": "mountain sunrise golden light forest",
        "cloudy": "misty mountains morning clouds",
        "rain": "rainy mountain valley clouds",
        "snow": "snowy mountains sunrise",
        "fog": "foggy forest morning",
    },
    "noon": {
        "clear": "mountain lake blue sky forest",
        "cloudy": "cloudy mountains green valley",
        "rain": "rainy forest path",
        "snow": "snowy mountain landscape winter",
        "fog": "fog mountains valley",
    },
    "afternoon": {
        "clear": "sunny mountains meadow blue sky",
        "cloudy": "overcast mountain valley",
        "rain": "rain mountains lake",
        "snow": "winter forest snow sunlight",
        "fog": "misty forest afternoon",
    },
    "night": {
        "clear": "starry night mountains",
        "cloudy": "cloudy night mountains moon",
        "rain": "rain night forest",
        "snow": "snowy mountains night",
        "fog": "foggy mountains dusk dark",
    },
}
# A photo tagged with one of these fits the weather. "clear" has no tags to ask for.
TAGS = {
    "cloudy": ("cloud", "overcast"),
    "rain": ("rain", "storm", "thunder", "wet"),
    "snow": ("snow", "winter"),
    "fog": ("fog", "mist", "haze"),
}
REMOTE = "/sdcard/Android/data/com.ansub.tvhome/files/wallpapers"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "wallpapers")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "pixabay-cache")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "tv-detox"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def search(url, key):
    """The answer for a search, kept for 24 hours as Pixabay asks, so running this again is free."""
    os.makedirs(CACHE, exist_ok=True)
    # Keyed on the search itself, never on the key.
    name = os.path.join(CACHE, urllib.parse.quote(url.replace(key, "K"), safe="")[-150:] + ".json")
    if os.path.exists(name) and time.time() - os.path.getmtime(name) < 24 * 3600:
        with open(name, encoding="utf-8") as f:
            return f.read()
    body = get(url).decode("utf-8")
    with open(name, "w", encoding="utf-8") as f:
        f.write(body)
    return body


def main():
    key = os.environ.get("PIXABAY_KEY", "").strip()
    if len(sys.argv) != 2 or not key:
        sys.exit("Usage: PIXABAY_KEY=your-key python3 fetch-wallpapers.py <TV_IP>")
    tv = sys.argv[1] if ":" in sys.argv[1] else sys.argv[1] + ":5555"
    subprocess.run(["adb", "connect", tv], stdout=subprocess.DEVNULL, check=False)
    if subprocess.run(["adb", "-s", tv, "get-state"], capture_output=True, text=True).stdout.strip() != "device":
        sys.exit("Can't reach " + tv + ". Is the TV on, with network debugging allowed?")
    os.makedirs(OUT, exist_ok=True)

    used, credits = set(), []
    for slot, kinds in SEARCH.items():
        for kind, query in kinds.items():
            # 100 requests a minute are allowed; this makes 20, one per photo.
            url = "https://pixabay.com/api/?" + urllib.parse.urlencode(
                {"key": key, "q": query, "image_type": "photo", "orientation": "horizontal",
                 "category": "nature", "min_width": 1280, "safesearch": "true", "per_page": 50})
            try:
                hits = json.loads(search(url, key))["hits"]
            except urllib.error.HTTPError as e:
                sys.exit("Pixabay said HTTP %d (a wrong key gives 400, too many requests 429)." % e.code)
            # Close to 16:9 only: a panorama cropped to the screen would be a blurry strip. Prefer the
            # biggest originals; the file we can download is fullHDURL with full API access, else
            # largeImageURL (1280 px).
            hits = [h for h in hits if h["id"] not in used
                    and 1.55 <= h.get("imageWidth", 0) / max(1, h.get("imageHeight", 1)) <= 1.95]
            # Pixabay's tags say what is in a photo: a "rain" search can return a sunny road. Prefer
            # photos tagged for the weather, then keep Pixabay's own order (most popular first).
            words = TAGS.get(kind)
            if words:
                tagged = [h for h in hits if any(w in h.get("tags", "").lower() for w in words)]
                hits = tagged or hits
            big = [h for h in hits if h.get("imageWidth", 0) >= 3000]
            pick = (big or hits or [None])[0]
            if not pick:
                print("  no photo for", slot, kind, "(" + query + ")")
                continue
            used.add(pick["id"])
            name = slot + "_" + kind + ".jpg"
            with open(os.path.join(OUT, name), "wb") as f:
                f.write(get(pick.get("fullHDURL") or pick["largeImageURL"]))
            credits.append(name + "|" + pick["user"] + "|" + pick["pageURL"])
            print("  " + name + "  " + pick["user"] + "  (" + str(pick.get("imageWidth")) + " px original)")

    with open(os.path.join(OUT, "credits.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(credits) + "\n")
    # The folder is made by the app, which has to have run once.
    subprocess.run(["adb", "-s", tv, "shell", "mkdir", "-p", REMOTE], check=False)
    for fn in sorted(os.listdir(OUT)):
        subprocess.run(["adb", "-s", tv, "push", os.path.join(OUT, fn), REMOTE + "/" + fn],
                       stdout=subprocess.DEVNULL, check=True)
    print("Pushed", len(credits), "photos to", tv, "- press Home on the TV.")


if __name__ == "__main__":
    main()
