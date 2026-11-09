# Buttons

A Kotlin / Compose Multiplatform app to interface to HomeAssistant — a very simplified "stream deck".

It shows a range of buttons - with display name under each.

These can be used to toggle on/off (lights, `input_boolean` helpers, switches…) or to trigger any
Home Assistant action (scripts, scenes, notifications…).

Status is displayed live (via the Home Assistant WebSocket API).

All config (url, token etc) is read from a config file.

## Running

Requires JDK 21 (a `mise.toml` is included: `mise install`).

```sh
mkdir -p ~/.config/buttons
cp config.example.yaml ~/.config/buttons/config.yaml   # then edit it
./gradlew :composeApp:run
```

Tests: `./gradlew :composeApp:desktopTest`

### Install (macOS)

```sh
./gradlew installLocal
buttons                       # or: buttons --config /path/to/config.yaml
```

This builds a self-contained `Buttons.app` (with its own trimmed Java runtime - no JDK needed to run it),
copies it to `~/Applications` (so it's also in Spotlight/Launchpad) and writes a `buttons` launcher script
to `~/.local/bin`. Override locations with `-Pbuttons.appDir=…` / `-Pbuttons.binDir=…`.

Other packaging: `./gradlew :composeApp:packageDmg` (or `packageDistributionForCurrentOS`).

### App icon

The icon is the Home Assistant logomark (trademark of the Open Home Foundation; personal, non-commercial
use only), rendered by `composeApp/icons/MakeIcon.java`. To regenerate:

```sh
cd composeApp/icons
java MakeIcon.java ha-logomark.svg icon-1024.png
# then rebuild buttons.icns / buttons.png with sips + iconutil
```

## Config

Location, in order of precedence:

1. `--config <path>` (with gradle: `./gradlew :composeApp:run --args="--config /path/config.yaml"`)
2. `BUTTONS_CONFIG` environment variable
3. `~/.config/buttons/config.yaml`

```yaml
homeassistant:
  url: https://homeassistant.lan:8123
  token: <long-lived access token>   # HA: Profile -> Security -> Long-lived access tokens
  ca_cert: ca.pem                    # optional, see TLS below

ui:
  columns: 4                         # optional, default 4

buttons:
  - name: Desk lamp
    entity: light.desk               # toggled via homeassistant.toggle
    icon: lightbulb
  - name: Movie time
    action: script.turn_on           # any action, domain.service
    target: { entity_id: script.movie_time }
    data: {}                         # optional service data
    icon: movie
```

Each button has either `entity` (toggle + live state) or `action` (fire-and-forget; the tile flashes
green on success, red on failure). Errors are shown in the status bar.

`icon` is optional; when omitted a default is chosen from the entity/action domain. Available names:
ac, air, alarm, bed, bell, bolt, camera, code, coffee, door, fan, fire, garage, heat, home, kitchen,
light, lightbulb, lock, moon, movie, music, night, notifications, palette, person, plant, play, plug,
power, scene, security, speaker, star, stop, sun, toggle, touch, tv, unlock, water.

### TLS

Certificates are always verified — there is no option to disable verification.

- **Nabu Casa / publicly trusted certificate** (e.g. `https://xxxx.ui.nabu.casa`): just set `url`;
  the system trust store is used.
- **Self-signed / private CA**: set `ca_cert` to a PEM file containing your CA certificate(s).
  When set, *only* those CAs are trusted for the connection. Relative paths are resolved against
  the config file's directory; `~/` is supported. The server certificate's hostname must still match `url`.
- **Plain `http://`** on a local network also works (no TLS); `ca_cert` is rejected in that case.
