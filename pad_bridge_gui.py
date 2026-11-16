"""
Pad Bridge for PC - window version.

Keep pad_bridge.py in the same folder as this file.
    pip install aiohttp vgamepad
    python pad_bridge_gui.py
"""
import asyncio
import json
import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox

import pad_bridge as pb

BG, CARD, LINE = "#13161c", "#1d2129", "#2a2f39"
TEXT, MUTE = "#eef1f6", "#8f98a6"
BLUE, RED, GREEN, AMBER, GREY = "#2d7ff9", "#e5484d", "#22b573", "#e0a100", "#5b6370"

CONFIG = Path.home() / ".pad_bridge" / "config.json"


def load_adb_path():
    try:
        p = json.loads(CONFIG.read_text()).get("adb")
        if p and Path(p).exists():
            return p
    except (OSError, ValueError):
        pass
    return pb.find_adb()


def find_apk():
    """The phone app, bundled inside the exe or placed next to the program."""
    here = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
    bundled = Path(getattr(sys, "_MEIPASS", here))
    for base in (bundled, here):
        for name in ("PadBridge.apk", "app-debug.apk"):
            if (base / name).exists():
                return str(base / name)
    return None


def find_driver_msi():
    """The ViGEmBus driver installer that ships inside the vgamepad package."""
    bases = []
    if getattr(sys, "_MEIPASS", None):
        bases.append(Path(sys._MEIPASS) / "vgamepad")
    try:
        import vgamepad
        bases.append(Path(vgamepad.__file__).parent)
    except ImportError:
        pass
    for base in bases:
        if base.exists():
            found = sorted(base.rglob("ViGEmBus*.msi")) or sorted(base.rglob("*.msi"))
            if found:
                return str(found[0])
    return None


def save_adb_path(p):
    CONFIG.parent.mkdir(exist_ok=True)
    CONFIG.write_text(json.dumps({"adb": p}))


class Bridge:
    """Runs the server in a background thread so the window never freezes."""

    def __init__(self):
        self.thread = None
        self.loop = None
        self.stop_event = None

    @property
    def running(self):
        return self.thread is not None and self.thread.is_alive()

    def start(self, adb):
        if self.running:
            return
        self.thread = threading.Thread(target=self._run, args=(adb,), daemon=True)
        self.thread.start()

    def _run(self, adb):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        self.stop_event = asyncio.Event()
        try:
            self.loop.run_until_complete(pb.serve(adb, self.stop_event))
        except OSError as e:
            pb.log(f"Could not start: {e} (is another Pad Bridge already running?)")
        except Exception as e:  # keep the window alive and show the reason
            pb.log(f"Error: {e}")
        finally:
            self.loop.close()

    def stop(self):
        if self.running and self.loop and self.stop_event:
            self.loop.call_soon_threadsafe(self.stop_event.set)
            self.thread.join(timeout=5)


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Pad Bridge")
        self.geometry("460x660")
        self.minsize(420, 580)
        self.configure(bg=BG)
        self.bridge = Bridge()
        self.logq = queue.Queue()
        pb.log = lambda m: self.logq.put(f"{time.strftime('%H:%M:%S')}  {m}")
        self.adb = load_adb_path()
        self.rows = {}
        self.build()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        if self.adb:
            self.toggle()
        else:
            pb.log("adb was not found. Click 'Find adb folder' and choose the platform-tools folder.")
        self.after(300, self.tick)

    # ----- layout -----
    def build(self):
        tk.Label(self, text="Pad Bridge", font=("Segoe UI", 22, "bold"), bg=BG, fg=TEXT).pack(
            anchor="w", padx=20, pady=(18, 0))
        tk.Label(self, text="Use your phone's controller on this PC over USB", font=("Segoe UI", 10),
                 bg=BG, fg=MUTE).pack(anchor="w", padx=20)

        card = tk.Frame(self, bg=CARD)
        card.pack(fill="x", padx=20, pady=16)
        for key, name in [("adb", "adb tool"), ("phone", "Phone (USB)"), ("app", "Phone app"),
                          ("pads", "Controller")]:
            row = tk.Frame(card, bg=CARD)
            row.pack(fill="x", padx=14, pady=7)
            dot = tk.Canvas(row, width=12, height=12, bg=CARD, highlightthickness=0)
            oval = dot.create_oval(1, 1, 11, 11, fill=GREY, outline="")
            dot.pack(side="left")
            tk.Label(row, text=name, width=12, anchor="w", bg=CARD, fg=MUTE,
                     font=("Segoe UI", 10)).pack(side="left", padx=(8, 0))
            lab = tk.Label(row, text="", anchor="w", bg=CARD, fg=TEXT, font=("Segoe UI", 10, "bold"))
            lab.pack(side="left", fill="x", expand=True)
            self.rows[key] = (dot, oval, lab)

        self.btn = tk.Button(self, text="Start", command=self.toggle, font=("Segoe UI", 13, "bold"),
                             bg=BLUE, fg="white", activebackground=BLUE, activeforeground="white",
                             relief="flat", bd=0, pady=10, cursor="hand2")
        self.btn.pack(fill="x", padx=20)

        for buttons in ([("Test controller", self.test_controller), ("Install driver", self.install_driver)],
                        [("Install phone app", self.install_app), ("Find adb folder", self.pick_adb)]):
            small = tk.Frame(self, bg=BG)
            small.pack(fill="x", padx=20, pady=(10, 0))
            for text, cmd in buttons:
                tk.Button(small, text=text, command=cmd, bg=LINE, fg=TEXT, relief="flat", bd=0,
                          activebackground=LINE, activeforeground=TEXT, pady=7, cursor="hand2",
                          font=("Segoe UI", 10)).pack(side="left", expand=True, fill="x", padx=(0, 6))
        tk.Frame(self, bg=BG, height=10).pack()

        tk.Label(self, text="Log", bg=BG, fg=MUTE, font=("Segoe UI", 9)).pack(anchor="w", padx=20)
        self.log = tk.Text(self, height=8, bg=CARD, fg=MUTE, relief="flat", bd=0, wrap="word",
                           font=("Consolas", 9), state="disabled", padx=10, pady=8)
        self.log.pack(fill="both", expand=True, padx=20, pady=(2, 8))
        tk.Label(self, text="Steps: plug in the phone, allow USB debugging, open the phone app, "
                            "type localhost:8080, tap Start.", wraplength=410, justify="left",
                 bg=BG, fg=MUTE, font=("Segoe UI", 9)).pack(anchor="w", padx=20, pady=(0, 14))

    # ----- actions -----
    def toggle(self):
        if self.bridge.running:
            self.bridge.stop()
        else:
            if not self.adb:
                self.pick_adb()
                if not self.adb:
                    return
            self.bridge.start(self.adb)

    def pick_adb(self):
        d = filedialog.askdirectory(title="Choose the platform-tools folder")
        if not d:
            return
        exe = Path(d) / "adb.exe"
        if not exe.exists():
            messagebox.showwarning("Pad Bridge", "adb.exe was not found in that folder.")
            return
        self.adb = str(exe)
        save_adb_path(self.adb)
        pb.log(f"Using adb at {self.adb}")

    def install_app(self):
        apk, adb = find_apk(), self.adb
        if not adb:
            pb.log("adb was not found, so the app cannot be installed yet.")
            return
        if not apk:
            pb.log("The phone app file was not found. Put PadBridge.apk next to this program.")
            return

        def work():
            pb.log("Installing the phone app. Keep the phone unlocked and watch its screen.")
            code, out = pb.run_adb(adb, "install", "-r", apk, timeout=180)
            if code == 0 and "Success" in out:
                pb.log("Phone app installed. Opening it...")
                pb.run_adb(adb, "shell", "monkey", "-p", "com.padbridge",
                           "-c", "android.intent.category.LAUNCHER", "1")
            elif "USER_RESTRICTED" in out:
                pb.log("The phone blocked the install. Turn on 'Install via USB' in "
                       "Developer options, then try again.")
            else:
                pb.log("Install failed: " + out.strip()[:200])

        threading.Thread(target=work, daemon=True).start()

    def install_driver(self):
        msi = find_driver_msi()
        if not msi:
            pb.log("The driver installer was not found inside this program.")
            return
        if not messagebox.askyesno("Pad Bridge",
                                   "Install the ViGEmBus driver?\n\nThis is needed once per PC. "
                                   "Windows will ask for permission."):
            return
        try:
            import ctypes
            r = ctypes.windll.shell32.ShellExecuteW(None, "runas", "msiexec.exe",
                                                    f'/i "{msi}" /passive', None, 1)
        except (AttributeError, OSError):
            r = 0
        pb.log("Installing the driver. Accept the Windows prompt, then restart Pad Bridge."
               if r > 32 else "The driver install was cancelled or could not start.")

    def test_controller(self):
        try:
            os.startfile("joy.cpl")
        except (AttributeError, OSError):
            subprocess.Popen(["control", "joy.cpl"])

    def on_close(self):
        self.bridge.stop()
        self.destroy()

    # ----- live status -----
    def set_row(self, key, color, text):
        dot, oval, lab = self.rows[key]
        dot.itemconfig(oval, fill=color)
        lab.config(text=text)

    def tick(self):
        st = pb.STATE
        running = self.bridge.running
        self.btn.config(text="Stop" if running else "Start", bg=RED if running else BLUE,
                        activebackground=RED if running else BLUE)

        if self.adb:
            self.set_row("adb", GREEN, "Found")
        else:
            self.set_row("adb", RED, "Not found")

        if not running:
            self.set_row("phone", GREY, "Stopped")
        else:
            p = st["phone"]
            if p == "ready":
                self.set_row("phone", GREEN, "Connected over USB")
            elif p == "unauthorized":
                self.set_row("phone", AMBER, "Tap Allow on the phone")
            elif p == "none":
                self.set_row("phone", AMBER, "Plug in the phone (USB debugging on)")
            else:
                self.set_row("phone", RED, str(p)[:40])

        if running and st["clients"] > 0:
            self.set_row("app", GREEN, "Connected")
        else:
            self.set_row("app", GREY, "Open the app and tap Start")

        if running and st["pads"] > 0:
            n = st["pads"]
            self.set_row("pads", GREEN, f"{n} virtual Xbox 360 pad{'s' if n > 1 else ''} active")
        else:
            self.set_row("pads", GREY, "Press a button on your controller")

        lines = []
        while True:
            try:
                lines.append(self.logq.get_nowait())
            except queue.Empty:
                break
        if lines:
            self.log.config(state="normal")
            self.log.insert("end", "\n".join(lines) + "\n")
            self.log.see("end")
            self.log.config(state="disabled")
        self.after(400, self.tick)


if __name__ == "__main__":
    App().mainloop()
