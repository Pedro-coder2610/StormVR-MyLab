# -*- coding: utf-8 -*-
"""
StormVR — Painel WEB. Abra no CELULAR (mesma rede Wi-Fi):  http://<IP-do-PC>:8787
Clone das abas do menu do StormVR dentro do SteamVR, em tempo real e nos DOIS sentidos:
  * mexeu no site  -> grava %LOCALAPPDATA%\\StormVR\\external.ini -> o driver aplica no VRSettings em ~0,1 s -> o menu do SteamVR mostra;
  * mexeu no SteamVR -> o driver publica o valor atual em state.ini (~0,3 s) -> o site atualiza sozinho.
Só biblioteca padrão do Python 3. Uso:  py stormvr_web.py [--port 8787] [--pin 1234]
"""
import os, sys, json, time, socket, threading, argparse, types
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
APPDIR = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "StormVR")
EXT, KEYS, STATE = (os.path.join(APPDIR, n) for n in ("external.ini", "keys.ini", "state.ini"))
MANIFEST = json.load(open(os.path.join(HERE, "manifest.json"), encoding="utf-8"))
TYPES = MANIFEST["types"]; DEFAULTS = MANIFEST["defaults"]
LOCK = threading.Lock()

# ---- telemetria pela memoria compartilhada do driver (reaproveita as estruturas do painel Python)
LINK = None
try:
    for m in ("tkinter", "tkinter.ttk", "tkinter.messagebox"): sys.modules.setdefault(m, types.ModuleType(m))
    sys.modules["tkinter"].Tk = object; sys.modules["tkinter"].ttk = sys.modules["tkinter.ttk"]; sys.modules["tkinter"].messagebox = sys.modules["tkinter.messagebox"]
    for cand in (os.path.join(HERE, "..", "painel_python"), HERE):
        if os.path.exists(os.path.join(cand, "stormvr_painel.py")): sys.path.insert(0, cand); break
    import stormvr_painel as P
    LINK = P.DriverLink()
except Exception as e:
    print("(telemetria indisponivel: %s)" % e)

def parse_ini(path):
    d = {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            for ln in f:
                ln = ln.strip()
                if len(ln) > 3 and ln[1] == ":" and "=" in ln:
                    k, v = ln[2:].split("=", 1); d[k] = v
    except OSError: pass
    return d

def conv(key, raw):
    t = TYPES.get(key, "f")
    if t == "b": return raw in ("1", "true", "True", True, 1)
    if t == "i": return int(round(float(raw)))
    return float(raw)

def fmt(key, v):
    t = TYPES.get(key, "f")
    if t == "b": return "1" if v else "0"
    if t == "i": return str(int(round(float(v))))
    return ("%.5g" % float(v))

VALUES = {}; RECENT = {}; STATE_MT = 0.0
def load_state():
    global STATE_MT
    try: mt = os.path.getmtime(STATE)
    except OSError: return
    if mt == STATE_MT: return
    STATE_MT = mt
    for k, v in parse_ini(STATE).items():
        if k in TYPES:
            try: VALUES[k] = conv(k, v)
            except ValueError: pass

def init_values():
    VALUES.update(DEFAULTS)
    for k, v in parse_ini(EXT).items():
        if k in TYPES:
            try: VALUES[k] = conv(k, v)
            except ValueError: pass
    os.makedirs(APPDIR, exist_ok=True)
    with open(KEYS, "w", encoding="utf-8") as f:                      # lista de chaves que o driver deve publicar em state.ini
        for k, t in TYPES.items(): f.write("%s:%s\n" % (t, k))

def write_ext(changes):
    with LOCK:
        cur = parse_ini(EXT)
        for k, v in changes.items(): cur[k] = fmt(k, v)
        tmp = EXT + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            for k, raw in cur.items():
                if k in TYPES: f.write("%s:%s=%s\n" % (TYPES[k], k, raw))
        os.replace(tmp, EXT)
        now = time.time()
        for k, v in changes.items(): VALUES[k] = v; RECENT[k] = now

def group_keys(what):
    tabs = {t["name"]: t["widgets"] for t in MANIFEST["tabs"]}
    def keys_of(ws): return [w["key"] for w in ws if "key" in w]
    if what == "all": return list(TYPES.keys())
    if what == "arm": return keys_of(tabs["Arm Model"])
    if what == "coord": return keys_of(tabs["Coordenadas"])
    if what == "pos":
        ws = tabs["Geral"]; i = next((n for n, w in enumerate(ws) if w.get("title", "").startswith("Posição")), 0)
        return keys_of(ws[i:])
    return []

def telemetry():
    out = {"alive": False}
    try:
        if LINK is None or not LINK.try_open() or not LINK.alive(): return out
        L = LINK.live; out["alive"] = True
        out["head"] = {"link": int(L.headLink), "rate": float(L.headRate), "ypr": [float(x) for x in L.headYpr], "up": [float(x) for x in L.hmdUp], "pos": [float(x) for x in L.hmdPos]}
        out["hands"] = {"link": int(L.handLink), "rate": float(L.handRate)}
        ctl = []
        for h in range(2):
            c = L.ctl[h]
            ctl.append({"link": int(c.link), "kind": int(c.kind), "rate": float(c.pktRate), "rssi": int(c.rssi), "age": float(c.ageMs) if c.ageMs < 1e6 else -1, "lost": int(c.lost),
                        "mpu": [float(x) for x in c.mpu], "out": [float(x) for x in c.out], "armOn": int(c.armOn),
                        "armJ": [[float(v) for v in j] for j in c.armJ], "posW": [float(x) for x in L.ctlPosW[h]]})
        out["ctl"] = ctl
        out["win"] = [int(x) for x in L.hmdWin]
        out["log"] = [bytes(L.log[i]).split(b"\0")[0].decode("utf-8", "replace") for i in range(8)]
    except Exception as e:
        out["error"] = str(e)
    return out

PIN = None
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        b = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(b))); self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(b)
    def _auth(self, q):
        return PIN is None or q.get("pin", [""])[0] == PIN or self.headers.get("X-Pin") == PIN
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path in ("/", "/index.html"):
            return self._send(200, open(os.path.join(HERE, "index.html"), "rb").read(), "text/html; charset=utf-8")
        if not self._auth(q): return self._send(401, '{"error":"pin"}')
        if u.path == "/manifest.json": return self._send(200, json.dumps(MANIFEST, ensure_ascii=False))
        if u.path == "/api/state":
            load_state()
            now = time.time(); vals = dict(VALUES)
            for k, t in list(RECENT.items()):
                if now - t > 1.5: RECENT.pop(k, None)
            return self._send(200, json.dumps({"values": vals, "t": telemetry(), "recent": list(RECENT.keys())}))
        self._send(404, '{"error":"nf"}')
    def do_POST(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if not self._auth(q): return self._send(401, '{"error":"pin"}')
        try: data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except ValueError: return self._send(400, '{"error":"json"}')
        if u.path == "/api/set":
            ch = {}
            items = data.get("values") or ({data["key"]: data["value"]} if "key" in data else {})
            for k, v in items.items():
                if k in TYPES:
                    try: ch[k] = conv(k, v)
                    except (ValueError, TypeError): pass
            if ch: write_ext(ch)
            return self._send(200, '{"ok":true}')
        if u.path == "/api/cmd":
            ok = bool(LINK and LINK.command(int(data.get("cmd", 0))))
            return self._send(200, json.dumps({"ok": ok}))
        if u.path == "/api/reset":
            ks = group_keys(data.get("what", ""))
            write_ext({k: DEFAULTS[k] for k in ks if k in DEFAULTS})
            return self._send(200, json.dumps({"ok": True, "n": len(ks)}))
        self._send(404, '{"error":"nf"}')

def lan_ips():
    ips = set()
    try:
        for ai in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET): ips.add(ai[4][0])
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("8.8.8.8", 80)); ips.add(s.getsockname()[0]); s.close()
    except OSError: pass
    return sorted(i for i in ips if not i.startswith("127."))

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--port", type=int, default=8787); ap.add_argument("--pin", default=None, help="senha simples exigida pelo site (opcional)")
    ap.add_argument("--driver", action="store_true", help="(uso interno) iniciado pelo driver junto com o SteamVR: sem console, encerra se a porta ja estiver em uso")
    a = ap.parse_args(); PIN = a.pin
    init_values(); load_state()
    try:
        srv = ThreadingHTTPServer(("0.0.0.0", a.port), H)
    except OSError as e:                                    # porta ocupada: ja existe um painel rodando
        print("Painel WEB: nao consegui abrir a porta %d (%s). Provavelmente ja esta rodando." % (a.port, e))
        if not a.driver: input("Enter para sair...")
        sys.exit(0)
    if a.driver:                                            # iniciado pelo driver: se o SteamVR (processo pai) morrer, este painel encerra junto
        import threading, time as _t
        _pp = os.getppid()
        def _watch():
            while True:
                _t.sleep(2.0)
                if os.getppid() != _pp: os._exit(0)
        threading.Thread(target=_watch, daemon=True).start()
    print("StormVR — painel web rodando. Abra no celular (mesma rede):")
    for ip in lan_ips() or ["<ip-do-pc>"]: print("   http://%s:%d" % (ip, a.port))
    print("(se o Windows perguntar sobre o Firewall, permita redes privadas)  Ctrl+C para sair.")
    try: srv.serve_forever()
    except KeyboardInterrupt: pass
    except Exception: pass
