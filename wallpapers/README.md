# Weather wallpapers

Twenty photos of mountains and woods, one for each part of the day (`morning`, `noon`,
`afternoon`, `night`) and each kind of weather (`clear`, `cloudy`, `rain`, `snow`, `fog`).
`install.sh` copies them to the TV, and the home screen picks one by the forecast and by sunrise
and sunset. `credits.txt` lists the photographer and the Pixabay page of each photo; the home
screen shows the photographer's name.

## Source and license

The photos are from [Pixabay](https://pixabay.com), downloaded with `fetch-wallpapers.py`,
resized to at most 1280 pixels wide. They are covered by
[Pixabay's license](https://pixabay.com/service/license-summary/), not by this repository's
license. That license allows free use without credit, but it does not allow distributing photos
on a "Standalone" basis, and it lists wallpapers as an example. Having them in this repository is
the fork owner's decision and risk. If you reuse the project, replace these photos with ones you
may redistribute (your own, or public domain), or delete this folder: the home screen then keeps
its plain gradient. `WALLPAPERS=off sh install.sh <TV_IP>` skips copying them.
