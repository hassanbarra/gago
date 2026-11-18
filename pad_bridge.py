"""
Pad Bridge (USB only) - use a Bluetooth gamepad on your Windows PC through your phone.

Gamepad --Bluetooth--> Phone --USB cable--> this script --> virtual Xbox 360 pad

Setup (Windows PC):
    pip install aiohttp vgamepad
    Put Android "platform-tools" (adb) in a folder named platform-tools next to
    this file, or have adb on your PATH.
    Phone: turn on Developer options > USB debugging, plug in the cable, and
    tap Allow on the phone when it asks.
    python pad_bridge.py

Then in the phone app type  localhost:8080  and tap Start.
(Or open http://localhost:8080 in Chrome on the phone.)
The server listens on this PC only, so nothing is exposed to your network.
"""
import asyncio
import json
import shutil
import subprocess
import sys
from pathlib import Path

from aiohttp import WSMsgType, web

import vgamepad as vg

DEBUG = "--debug" in sys.argv   # print every message from the phone
log = print          # the GUI replaces this to show messages in its window
STATE = {"adb": None, "phone": "none", "clients": 0, "pads": 0, "port": 8080}   # read by the GUI
PORT = 8080        # plain HTTP, this PC only (reached from the phone through adb)
B = vg.XUSB_BUTTON
# W3C "standard" gamepad button index -> Xbox 360 button
BUTTONS = {
    0: B.XUSB_GAMEPAD_A, 1: B.XUSB_GAMEPAD_B, 2: B.XUSB_GAMEPAD_X, 3: B.XUSB_GAMEPAD_Y,
    4: B.XUSB_GAMEPAD_LEFT_SHOULDER, 5: B.XUSB_GAMEPAD_RIGHT_SHOULDER,
    8: B.XUSB_GAMEPAD_BACK, 9: B.XUSB_GAMEPAD_START,
    10: B.XUSB_GAMEPAD_LEFT_THUMB, 11: B.XUSB_GAMEPAD_RIGHT_THUMB,
    12: B.XUSB_GAMEPAD_DPAD_UP, 13: B.XUSB_GAMEPAD_DPAD_DOWN,
    14: B.XUSB_GAMEPAD_DPAD_LEFT, 15: B.XUSB_GAMEPAD_DPAD_RIGHT,
    16: B.XUSB_GAMEPAD_GUIDE,
}

PAGE = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pad Bridge</title>
<style>
 body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:24px;text-align:center}
 button{font-size:1.3rem;padding:14px 36px;border:0;border-radius:12px;background:#2d7ff9;color:#fff}
 .card{background:#1c1c1c;border-radius:12px;padding:12px;margin:12px auto;max-width:420px;text-align:left}
 #status{margin:16px 0;color:#9aa}
 small{color:#888}
</style></head><body>
<h2>Pad Bridge</h2>
<div id="status">Not started</div>
<button id="go">Start</button>
<div id="pads"></div>
<p><small>Keep this tab open. The screen stays awake while running.</small></p>
<script>
let ws, timer, wake, last = {};
const $ = id => document.getElementById(id);
const r2 = v => Math.round(v * 100) / 100;

async function start() {
  $('go').disabled = true;
  try { wake = await navigator.wakeLock.request('screen'); } catch (e) {}
  ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
  ws.onopen = () => { $('status').textContent = 'Connected to PC'; timer = setInterval(poll, 8); };
  ws.onclose = () => { clearInterval(timer); $('status').textContent = 'Disconnected'; $('go').disabled = false; last = {}; };
  ws.onmessage = ev => {
    const m = JSON.parse(ev.data);
    if (m.rumble) {
      const g = navigator.getGamepads()[m.i];
      if (g && g.vibrationActuator) {
        g.vibrationActuator.playEffect('dual-rumble', {duration: 120,
          strongMagnitude: m.strong, weakMagnitude: m.weak}).catch(() => {});
      } else if (navigator.vibrate && (m.strong || m.weak)) navigator.vibrate(60);
    }
  };
}

function poll() {
  const pads = navigator.getGamepads();
  let html = '';
  for (const g of pads) {
    if (!g) continue;
    html += '<div class="card">#' + g.index + ' ' + g.id.slice(0, 40) + '</div>';
    const b = g.buttons.map(x => r2(x.value));
    const a = g.axes.slice(0, 4).map(r2);
    const key = JSON.stringify([b, a]);
    if (last[g.index] !== key && ws.readyState === 1) {
      last[g.index] = key;
      ws.send(JSON.stringify({i: g.index, b, a}));
    }
  }
  for (const i in last) {
    if (!pads[i] && ws.readyState === 1) { ws.send(JSON.stringify({i: +i, gone: true})); delete last[i]; }
  }
  $('pads').innerHTML = html || '<div class="card">No controller detected. Press a button on it.</div>';
}
$('go').onclick = start;
</script></body></html>"""


def apply_state(pad, d):
    b, a = d.get("b", []), d.get("a", [0, 0, 0, 0])
    for idx, btn in BUTTONS.items():
        if idx < len(b) and b[idx] > 0.5:
            pad.press_button(button=btn)
        else:
            pad.release_button(button=btn)
    pad.left_trigger_float(value_float=b[6] if len(b) > 6 else 0.0)
    pad.right_trigger_float(value_float=b[7] if len(b) > 7 else 0.0)
    # Browser Y axis: up = -1. Xbox: up = +1.
    pad.left_joystick_float(x_value_float=a[0], y_value_float=-a[1])
    pad.right_joystick_float(x_value_float=a[2], y_value_float=-a[3])
    pad.update()


def neutral(pad):
    pad.reset()
    pad.update()


async def index(_):
    return web.Response(text=PAGE, content_type="text/html")


async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=5)
    await ws.prepare(request)
    loop = asyncio.get_running_loop()
    pads = {}
    STATE["clients"] += 1
    log(f"Phone app connected ({request.remote})")

    def make_pad(i):
        pad = vg.VX360Gamepad()

        def rumble(client, target, large, small, led, user_data):
            msg = json.dumps({"rumble": 1, "i": i, "strong": large / 255, "weak": small / 255})
            loop.call_soon_threadsafe(asyncio.ensure_future, ws.send_str(msg))

        pad.register_notification(callback_function=rumble)
        return pad

    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            d = json.loads(msg.data)
            if DEBUG:
                log("from phone: " + msg.data)
            i = d.get("i", 0)
            if d.get("gone"):
                if i in pads:
                    neutral(pads.pop(i))
                    STATE["pads"] -= 1
                continue
            if i not in pads:
                try:
                    pads[i] = make_pad(i)
                except Exception as e:
                    log(f"Could not create the virtual controller: {e}")
                    log("Install the ViGEmBus driver (click Install driver), then restart this program.")
                    break
                STATE["pads"] += 1
                log(f"Virtual Xbox 360 pad created for controller #{i}")
            apply_state(pads[i], d)
    finally:
        # Connection lost: never leave a button stuck down.
        for pad in pads.values():
            neutral(pad)
        STATE["pads"] -= len(pads)
        pads.clear()
        STATE["clients"] -= 1
        log("Phone app disconnected")
    return ws


def find_adb():
    here = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
    bundled = Path(getattr(sys, "_MEIPASS", here))
    cands = [shutil.which("adb"), here / "adb.exe", here / "platform-tools" / "adb.exe",
             here / "platform-tools" / "adb", bundled / "platform-tools" / "adb.exe"]
    for c in cands:
        if c and Path(c).exists():
            return str(c)
    return None


NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)   # stops console windows flashing on Windows


def run_adb(adb, *args, timeout=15):
    try:
        r = subprocess.run([adb, *args], capture_output=True, text=True, timeout=timeout,
                           creationflags=NO_WINDOW)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except (OSError, subprocess.SubprocessError) as e:
        return 1, str(e)


def phone_state(adb):
    _, out = run_adb(adb, "devices")
    rows = [l.split() for l in out.strip().splitlines()[1:] if len(l.split()) >= 2]
    if any(r[1] == "device" for r in rows):
        return "ready"
    if any(r[1] == "unauthorized" for r in rows):
        return "unauthorized"
    return "none"


MESSAGES = {
    "none": "Waiting for the phone... plug it in with the USB cable and turn on USB debugging.",
    "unauthorized": "Look at the phone screen and tap Allow on the USB debugging question.",
    "ready": "Phone connected over USB. In the app type  localhost:%d  and tap Start." % PORT,
}


async def usb_watcher(adb):
    """Keep the USB link alive: re-create it whenever the phone is plugged in again."""
    last = None
    while True:
        state = await asyncio.to_thread(phone_state, adb)
        if state == "ready":
            pc_port = STATE["port"]
            _, lst = await asyncio.to_thread(run_adb, adb, "reverse", "--list")
            if f"tcp:{PORT} tcp:{pc_port}" not in lst:
                # phone's localhost:8080  ->  this PC's port (replaces any old link)
                code, out = await asyncio.to_thread(run_adb, adb, "reverse", f"tcp:{PORT}", f"tcp:{pc_port}")
                if code != 0:
                    state = "error: " + out.strip()
        STATE["phone"] = state
        if state != last:
            log(MESSAGES.get(state, state))
            last = state
        await asyncio.sleep(3)


async def serve(adb=None, stop=None):
    """Run the bridge. With stop=None (command line) it runs until Ctrl+C."""
    adb = adb or find_adb()
    STATE["adb"] = adb
    if not adb:
        log("adb was not found.")
        log("Download Android platform-tools (search: Android SDK platform-tools),")
        log("unzip it, and put the folder named platform-tools next to this program.")
        if stop is None:
            input("Press Enter to close.")
        return
    app = web.Application()
    app.add_routes([web.get("/", index), web.get("/ws", ws_handler)])
    runner = web.AppRunner(app)
    await runner.setup()
    try:
        await web.TCPSite(runner, "127.0.0.1", PORT).start()
    except OSError:
        # Port 8080 is taken on this PC: use any free port. The phone app is unaffected,
        # because adb maps the phone's localhost:8080 to whichever port we use here.
        await web.TCPSite(runner, "127.0.0.1", 0).start()
        log(f"Port {PORT} is busy on this PC, so another port is used. "
            f"The phone app still uses localhost:{PORT}.")
    STATE["port"] = runner.addresses[0][1]
    log("Pad Bridge is running (USB only). Leave it open while you play.")
    watcher = asyncio.create_task(usb_watcher(adb))
    try:
        if stop is None:
            await watcher
        else:
            await stop.wait()
    finally:
        watcher.cancel()
        await asyncio.to_thread(run_adb, adb, "reverse", "--remove", f"tcp:{PORT}")
        await runner.cleanup()
        STATE["phone"] = "none"
        log("Pad Bridge stopped.")


def main():
    try:
        asyncio.run(serve())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
