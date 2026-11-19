# TV Home

A calm, ad-free home screen for Google TV and Android TV. Your apps in the order you like, the clock and the
outdoor temperature, and a background that's actually worth looking at: NASA's picture of the day, a live sky
that matches the weather outside your window, or slow drifting space and aurora scenes.

It sits on top of Google TV instead of replacing it. Nothing on the TV is disabled or removed, and uninstalling
it puts everything back exactly as it was.

![The TV Home screen, with NASA's picture of the day behind it](docs/images/home-apod.jpg)

**Live weather**: the background that shows your real sky right now.

| Sunset | Rain | Night |
|---|---|---|
| ![Live weather at sunset](docs/images/sky-sunset.jpg) | ![Live weather in the rain](docs/images/sky-rain.jpg) | ![Live weather at night, with the real moon](docs/images/sky-night.jpg) |
| **Snow** | **Thunderstorm** | **Sun behind a cloud** |
| ![Live weather in snow](docs/images/sky-snow.jpg) | ![Live weather in a thunderstorm](docs/images/sky-storm.jpg) | ![Live weather, cloudy](docs/images/sky-cloudy.jpg) |

## What it does

- **Your apps, your order.** The Apps row shows your favourites; hold OK on an app to move it or take it off, and
  add more from *All apps*. No sponsored rows, no "recommended for you".
- **HDMI inputs** appear only when something is plugged in and switched on.
- **Clock with the outdoor temperature** and a little weather icon. Weather is optional (Settings → Weather);
  type a ZIP code or town to set where it's for, or let TV Home work out your area.
- **Backgrounds** (Settings → Background):
  - *NASA picture of the day*, slowly panning.
  - *Live weather*: your real sky right now, drawn from the current weather where you are. Clouds come and go,
    rain falls harder or softer and slants with the wind, snow, fog, lightning in a thunderstorm. The sun is
    NASA's live picture of it (today's real sunspots) and sits where the sun actually is in your sky; at night
    the moon is NASA's picture of tonight's moon, in its real phase and position.
  - *Deep space*, *Aurora*, *Glow* and *Plain*.
- **Album art** fills the background while music plays (Spotify and others), with the track shown up top.
- **Seasonal touches**: falling leaves in autumn, snow in winter, fireworks on New Year's Eve and the 4th of July,
  hearts on Valentine's Day. Leaves and snow drift with the real wind and swirl when a gust comes through.
- **Ambient clock** after two minutes: a big quiet clock over the background. Prefer the TV's own screensaver
  (Google's photo slideshows)? Settings → When idle turns the clock off.
- **Remote sounds**: a soft pop as the selection moves and a deeper one on OK, quieter than most TVs' own UI sounds
  (Settings → Sounds switches them off).
- **Home button (optional).** Google TV doesn't let other apps take over the Home button, so TV Home includes a
  small helper that can send Home to TV Home instead. It's off until you turn it on.

## Weather location

Weather is optional: **Settings → Weather → Weather and temperature** turns it off completely (no weather, no
location lookups). To choose where it's for, open **Settings → Weather → Weather location**, type a ZIP or postal
code (or a town name) with the remote, and pick the right place if there's more than one. From then on TV Home uses
exactly that place and stops guessing your area from your internet address. **Automatic** switches back.

| Weather settings | Typing a ZIP code |
|---|---|
| ![Settings, showing the weather section with the location set to New York](docs/images/settings-weather.jpg) | ![The Weather location box with a ZIP code typed in](docs/images/weather-location-zip.jpg) |

## Install

You'll need a computer on the same Wi-Fi as the TV for about 20 minutes. Step-by-step instructions, no
experience needed: **[INSTALL.md](INSTALL.md)**.

The APK is on the [Releases](../../releases) page.

## Privacy

No accounts, no analytics, no ads. TV Home only talks to these, and only for what's on your screen:

| What | Who | When |
|---|---|---|
| Picture of the day | NASA (science.nasa.gov) | Once a day, if that background is chosen |
| Weather | [Open-Meteo](https://open-meteo.com) (your approximate coordinates) | Every 15 minutes while TV Home is on screen |
| Your approximate location for the weather | ipapi.co, or ipwho.is if that fails (city level, from your internet address) | Once a week, and only until you set a location yourself |
| Finding a place you type in Settings | Open-Meteo's place search | When you search |
| Live sun and moon pictures | NASA (sdo.gsfc.nasa.gov, svs.gsfc.nasa.gov) | While *Live weather* is showing: sun every 20 min, moon hourly |

Turning off *Settings → Weather and temperature* stops the weather and location lookups entirely. To skip the
automatic location, type your ZIP code or town in *Settings → Weather location*.

The helper behind the Home button option is an Android accessibility service, because that's the only way an app
can hear the Home button on Google TV. It only listens for the Home key and for Google's home screen opening; it
doesn't read anything on screen.

## Editions

- **TV Home** (this repository): works on its own.
- **[TV Home for Home Assistant](https://github.com/CESMOtacon/tv-home-ha)**: the same app plus Home Assistant:
  light-show and scene tiles, every camera on one screen in HD, a doorbell pop-up over whatever you're watching
  (with quick spoken replies), and a Live weather sky driven by your own weather station.

## Building from source

Android Studio, or JDK 17+ and the Android SDK:

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed only if you add a `keystore.properties` (see `app/build.gradle.kts`).

Written in Kotlin with plain Android views; everything is drawn in 1920×1080 "design pixels" and scaled to the
screen. `Edition.kt` is the one file the Home Assistant edition replaces.

## Credits

- Weather data by [Open-Meteo.com](https://open-meteo.com/) ([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)).
- Astronomy Picture of the Day: [NASA](https://science.nasa.gov/apod/) (each picture's credit is shown with it).
- Sun: NASA's [Solar Dynamics Observatory](https://sdo.gsfc.nasa.gov/) (HMI). Moon: NASA Scientific Visualization
  Studio, [Dial-a-Moon](https://svs.gsfc.nasa.gov/help/#apis-dialamoon).
- Not affiliated with Google, NASA or Open-Meteo.

## License

[MIT](LICENSE)
