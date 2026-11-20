# -*- coding: utf-8 -*-
"""
StormVR - Painel (Python). Abre DIRETO no Windows, sem o SteamVR aberto, e as mudancas valem em TEMPO REAL.

Como funciona
  * Cada ajuste e gravado em  %LOCALAPPDATA%\\StormVR\\external.ini . O driver (dentro do SteamVR) olha esse arquivo ~2x por segundo
    e aplica tudo no VRSettings na hora (o menu da dashboard do SteamVR enxerga os mesmos valores).
  * Com o SteamVR FECHADO o arquivo apenas guarda os valores; o driver aplica todos quando o SteamVR abrir.
  * Comandos (recentrar, calibrar giroscopio...) e a telemetria usam a memoria compartilhada do driver (so com o SteamVR aberto).

Uso:  py stormvr_painel.py        (precisa so do Python 3 do Windows, com tkinter - ja vem no instalador oficial)
"""
import os, sys, json, time, ctypes, tkinter as tk
from tkinter import ttk, messagebox

APPDIR = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "StormVR")
INI = os.path.join(APPDIR, "external.ini")

# --------------------------------------------------------------------------- tabela de ajustes (espelha pc_driver/common/common.h)
# (aba, chave, tipo, rotulo, padrao, minimo, maximo, passo)   tipo: f = decimal, i = inteiro, b = liga/desliga, c = escolha (lista)
CH = {
    "arm_mode": ["Completo (ombro + cotovelo)", "Simples (esfera / cabo)"],
    "src_mode": ["Auto (ESP32, senão teclado)", "Só ESP32", "Só teclado"],
    "esp_channel": ["Auto", "SteamVR (Wi-Fi)", "Android (Bluetooth)"],
    "track_mode": ["3DoF (altura fixa)", "6DoF (posição do app)"],
}
SETTINGS = [
    # ---- Cabeça
    ("Cabeça", "head_smooth", "f", "Suavização da cabeça (%)  — tira o tremor", 35, 0, 100, 1),
    ("Cabeça", "head_predict_ms", "f", "Previsão da cabeça (ms)  — compensa a latência", 20, 0, 60, 1),
    ("Cabeça", "world_yaw", "f", "Girar o mundo (°)", 0, -180, 180, 1),
    ("Cabeça", "head_roll", "f", "Rolagem da imagem (°)  — 180 se o jogo estiver de cabeça pra baixo", 0, -180, 180, 1),
    ("Cabeça", "user_height", "f", "Altura do usuário (m)", 1.65, 1.0, 2.6, 0.01),
    ("Cabeça", "head_height_off", "f", "Ajuste fino da altura (m)", 0.0, -1.5, 2.0, 0.01),
    ("Cabeça", "head_flip_180", "b", "Girar a cabeça 180° no eixo da frente (corrige de cabeça pra baixo)", False, 0, 1, 1),
    ("Cabeça", "track_mode", "c", "Modo de rastreamento", 1, 0, 1, 1),
    ("Cabeça", "head_timeout", "f", "Tempo sem pose = rastreamento perdido (s)", 0.5, 0.1, 3.0, 0.05),
    # ---- Controles
    ("Controles", "ctl_quat", "b", "Orientação por quaternion (corrige apontar p/ cima e p/ baixo)", True, 0, 1, 1),
    ("Controles", "ctl_smooth", "f", "Suavização dos controles (%)", 25, 0, 100, 1),
    ("Controles", "predict_ms", "f", "Previsão de movimento dos controles (ms)", 15, 0, 60, 1),
    ("Controles", "esp_timeout", "f", "Tempo sem pacote do ESP32 = perdido (s)", 0.5, 0.1, 3.0, 0.05),
    ("Controles", "src_mode", "c", "Fonte dos controles", 0, 0, 2, 1),
    ("Controles", "esp_channel", "c", "Canal do ESP32", 0, 0, 2, 1),
    ("Controles", "ctl_swap", "b", "Trocar direita ↔ esquerda", False, 0, 1, 1),
    ("Controles", "combo_recenter", "b", "L3 + Start recentra o yaw", True, 0, 1, 1),
    ("Controles", "auto_center", "b", "Recentrar o yaw ao conectar", True, 0, 1, 1),
    ("Controles", "hide_lost", "b", "Esconder o controle se o ESP32 cair (modo Só ESP32)", False, 0, 1, 1),
    # ---- Braço
    ("Braço", "arm_enabled", "b", "Ativar modelo de braço virtual", False, 0, 1, 1),
    ("Braço", "arm_mode", "c", "Modo", 1, 0, 1, 1),
    ("Braço", "arm_simple_len", "f", "Simples: comprimento do cabo / raio da esfera (m)", 0.30, 0.05, 1.0, 0.01),
    ("Cabeça", "head_hold", "f", "Segurar a pose do headset (s) sem pacotes antes de declarar rastreamento perdido (evita tela cinza piscando)", 5.0, 0.5, 30.0, 0.5),
    ("Cabeça", "hmd_monitor_mode", "b", "Monitor comum como headset (imagem única, sem lente; reinicie o SteamVR)", False, 0, 1, 1),
    ("Cabeça", "hmd_anti_idle", "b", "Impedir tela preta com o headset parado (tela não apaga + micro-movimento)", True, 0, 1, 1),
    ("Braço", "pos6_scale", "f", "6DoF do celular-controle: escala da posição", 1.0, 0.25, 3.0, 0.05),
    ("Braço", "pos6_src_x", "i", "6DoF: eixo X (direita) ← 0 frente / 1 esquerda / 2 cima", 1, 0, 2, 1),
    ("Braço", "pos6_src_y", "i", "6DoF: eixo Y (cima) ← 0 frente / 1 esquerda / 2 cima", 2, 0, 2, 1),
    ("Braço", "pos6_src_z", "i", "6DoF: eixo Z (trás) ← 0 frente / 1 esquerda / 2 cima", 0, 0, 2, 1),
    ("Braço", "pos6_inv_x", "b", "6DoF: inverter X", True, 0, 1, 1),
    ("Braço", "pos6_inv_y", "b", "6DoF: inverter Y", False, 0, 1, 1),
    ("Braço", "pos6_inv_z", "b", "6DoF: inverter Z", True, 0, 1, 1),
    ("Braço", "arm_simple_elbow", "f", "Simples: altura do COTOVELO (centro da esfera) abaixo do ombro (m)", 0.28, 0.0, 0.7, 0.01),
    ("Braço", "arm_sh_x", "f", "Ombro para o lado (m)", 0.17, 0.05, 0.30, 0.01),
    ("Braço", "arm_sh_drop", "f", "Ombro abaixo da cabeça (m)", 0.20, 0.05, 0.40, 0.01),
    ("Braço", "arm_upper", "f", "Completo: ombro → cotovelo (m)", 0.30, 0.15, 0.50, 0.01),
    ("Braço", "arm_fore", "f", "Completo: cotovelo → mão (m)", 0.32, 0.15, 0.50, 0.01),
    ("Braço", "arm_bend_up", "f", "Completo: cotovelo recua ao subir a mão", 0.35, 0, 1, 0.01),
    ("Braço", "arm_bend_down", "f", "Completo: cotovelo vai à frente ao descer a mão", 0.55, 0, 1, 0.01),
    ("Braço", "arm_side_reach", "f", "Completo: alcance extra para os lados", 0.60, 0, 2, 0.01),
    ("Braço", "arm_torso", "f", "Completo: giro do tronco junto", 0.25, 0, 1, 0.01),
    ("Braço", "arm_reach", "f", "Completo: escala geral do alcance", 1.0, 0.6, 1.6, 0.01),
    # ---- Mãos
    ("Mãos", "hands_smooth", "f", "Suavização da posição das mãos rastreadas (%)", 45, 0, 100, 1),
    ("Mãos", "hands_pos_right", "b", "Mão direita: posição → controle", True, 0, 1, 1),
    ("Mãos", "hands_pos_left", "b", "Mão esquerda: posição → controle", True, 0, 1, 1),
    ("Mãos", "hands_pinch_right", "b", "Mão direita: pinça → trigger", True, 0, 1, 1),
    ("Mãos", "hands_pinch_left", "b", "Mão esquerda: pinça → trigger", True, 0, 1, 1),
    ("Mãos", "hands_aim_right", "b", "Mão direita: direção da mão → orientação", True, 0, 1, 1),
    ("Mãos", "hands_aim_left", "b", "Mão esquerda: direção da mão → orientação", True, 0, 1, 1),
    ("Mãos", "hand_timeout", "f", "Tempo sem pacote de mãos = perdidas (s)", 0.4, 0.1, 2.0, 0.05),
    # ---- Headset / janela
    ("Headset", "hmd_enabled", "b", "Criar o headset virtual", True, 0, 1, 1),
    ("Headset", "hmd_force_fs", "b", "Forçar a janela do compositor em tela cheia", True, 0, 1, 1),
    ("Headset", "hmd_make_primary", "b", "Tornar o monitor do headset o principal enquanto o SteamVR roda", True, 0, 1, 1),
    ("Headset", "hmd_win_x", "i", "Janela: X", 0, -32768, 32768, 10),
    ("Headset", "hmd_win_y", "i", "Janela: Y", 0, -32768, 32768, 10),
    ("Headset", "hmd_win_w", "i", "Janela: largura (2 olhos lado a lado)", 1920, 640, 7680, 10),
    ("Headset", "hmd_win_h", "i", "Janela: altura", 1080, 360, 4320, 10),
    ("Headset", "hmd_fov_h", "f", "FOV horizontal de cada olho (°)", 90, 40, 130, 1),
    ("Headset", "hmd_fov_v", "f", "FOV vertical (°)", 90, 40, 130, 1),
    ("Headset", "hmd_ipd", "f", "Distância entre os olhos (m)", 0.063, 0.050, 0.080, 0.001),
    ("Headset", "hmd_sep", "f", "Separação das 2 imagens (%)", 0.0, -10, 10, 0.1),
    ("Headset", "hmd_hz", "f", "Taxa de atualização (Hz)", 60, 30, 120, 1),
    ("Headset", "hmd_render_scale", "f", "Escala de renderização", 1.0, 0.5, 2.0, 0.05),
    ("Headset", "hmd_k1", "f", "Distorção K1", 0.34, -1, 2, 0.01),
    ("Headset", "hmd_k2", "f", "Distorção K2", 0.55, -1, 2, 0.01),
    # ---- Gamepad / rede
    ("Rede", "udp_port", "i", "Porta UDP do controle direito", 5555, 1024, 65535, 1),
    ("Rede", "udp_port_left", "i", "Porta UDP do controle esquerdo", 5558, 1024, 65535, 1),
    ("Rede", "head_port", "i", "Porta UDP da cabeça", 5556, 1024, 65535, 1),
    ("Rede", "hand_port", "i", "Porta UDP das mãos", 5557, 1024, 65535, 1),
]
RISKY = {("hmd_make_primary", True): "Isto muda o monitor PRINCIPAL do Windows enquanto o SteamVR estiver aberto. Continuar?",
         ("hmd_enabled", False): "Desligar o headset virtual remove o visor do SteamVR (os controles continuam). Continuar?"}
COMMANDS = [("Recentralizar a cabeça", 4), ("Recentralizar controle ESQUERDO", 11), ("Recentralizar controle DIREITO", 10),
            ("Recentrar yaw dos dois controles", 0), ("Zerar todos os eixos", 1), ("Calibrar giroscópio", 2),
            ("Centrar analógico", 3), ("Reiniciar portas UDP", 5)]
CMD_COUNT = 14

# --------------------------------------------------------------------------- persistencia (external.ini)
def fmt(kind, v):
    if kind == "b": return "1" if v else "0"
    if kind in ("i", "c"): return str(int(round(float(v))))
    return ("%.4f" % float(v)).rstrip("0").rstrip(".") or "0"

def type_prefix(kind): return {"f": "f", "i": "i", "c": "i", "b": "b"}[kind]

def steamvr_current():
    """Valores ATUAIS do driver no steamvr.vrsettings (para os controles mostrarem o que ja esta valendo)."""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
            steam = winreg.QueryValueEx(k, "SteamPath")[0]
        with open(os.path.join(steam, "config", "steamvr.vrsettings"), "r", encoding="utf-8") as f:
            return json.load(f).get("driver_stormvr", {})
    except Exception:
        return {}

def steam_dir():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
            return winreg.QueryValueEx(k, "SteamPath")[0]
    except Exception:
        return None

def chaperone_path():
    d = steam_dir()
    return os.path.join(d, "config", "chaperone_info.vrchap") if d else None

def chaperone_report():
    """Mostra o que o SteamVR guardou de salas antigas (universos). Yaw perto de ±3.14 = mundo girado 180 graus."""
    p = chaperone_path()
    if not p or not os.path.exists(p): return "chaperone_info.vrchap: não encontrado (ok: SteamVR usa o padrão)."
    try:
        with open(p, "r", encoding="utf-8") as f: j = json.load(f)
    except Exception as e:
        return "chaperone_info.vrchap ilegível: %s" % e
    out = ["chaperone_info.vrchap: %d universo(s)" % len(j.get("universes", []))]
    for u in j.get("universes", []):
        st = u.get("standing", {}); se = u.get("seated", {})
        out.append("  universo %s | standing yaw %.2f rad, pos %s | seated yaw %.2f rad" % (u.get("universeID"), st.get("yaw", 0.0),
                   [round(x, 2) for x in st.get("translation", [])], se.get("yaw", 0.0)))
    return "\n".join(out)

def chaperone_reset():
    """Move o chaperone_info.vrchap para um backup (SteamVR recria limpo). Devolve texto do resultado."""
    p = chaperone_path()
    if not p or not os.path.exists(p): return "Nada para resetar."
    bak = p + ".bak-" + time.strftime("%Y%m%d-%H%M%S")
    os.replace(p, bak)
    return "Backup salvo em:\n%s\nAbra o SteamVR: ele cria um chaperone novo e limpo." % bak

def load_ini():
    vals = {}
    try:
        with open(INI, "r", encoding="utf-8") as f:
            for ln in f:
                ln = ln.strip()
                if len(ln) > 3 and ln[1] == ":" and "=" in ln:
                    k, v = ln[2:].split("=", 1)
                    vals[k] = v
    except OSError:
        pass
    return vals

def save_ini(values, only=None):
    os.makedirs(APPDIR, exist_ok=True)
    tmp = INI + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for (_, key, kind, _l, _d, _mn, _mx, _st) in SETTINGS:
            if key in values and (only is None or key in only):
                f.write("%s:%s=%s\n" % (type_prefix(kind), key, fmt(kind, values[key])))
    os.replace(tmp, INI)          # troca atomica: o driver nunca le um arquivo pela metade

# --------------------------------------------------------------------------- memoria compartilhada do driver (comandos + telemetria)
class CtlLive(ctypes.Structure):
    _fields_ = [("link", ctypes.c_uint32), ("kind", ctypes.c_uint32), ("pktRate", ctypes.c_float), ("rssi", ctypes.c_int32),
                ("buttons", ctypes.c_uint32), ("ageMs", ctypes.c_float), ("lost", ctypes.c_uint32), ("dropped", ctypes.c_uint32),
                ("flags", ctypes.c_uint32), ("outBits", ctypes.c_uint64), ("orient", ctypes.c_uint32),
                ("rawRpy", ctypes.c_float * 3), ("mpu", ctypes.c_float * 3), ("out", ctypes.c_float * 3), ("gyro", ctypes.c_float * 3),
                ("joyRaw", ctypes.c_uint16 * 2), ("stickNorm", ctypes.c_float * 2), ("stick", ctypes.c_float * 2),
                ("uptimeMs", ctypes.c_uint32), ("ip", ctypes.c_char * 16), ("armOn", ctypes.c_uint32), ("armJ", (ctypes.c_float * 3) * 3)]

class Live(ctypes.Structure):
    _fields_ = [("version", ctypes.c_uint32), ("cmd", ctypes.c_int32 * CMD_COUNT), ("profile", ctypes.c_uint32), ("outBits", ctypes.c_uint64),
                ("ctl", CtlLive * 2), ("srcSeen", ctypes.c_uint32), ("srcUnassigned", ctypes.c_uint32), ("extraIp", ctypes.c_char * 16),
                ("handLink", ctypes.c_uint32), ("handRate", ctypes.c_float), ("handIp", ctypes.c_char * 16),
                ("handTracked", ctypes.c_uint32 * 2), ("handPinchNow", ctypes.c_uint32 * 2), ("handPosUsed", ctypes.c_uint32 * 2),
                ("headLink", ctypes.c_uint32), ("headRate", ctypes.c_float), ("headYpr", ctypes.c_float * 3), ("headIp", ctypes.c_char * 16),
                ("hmdWin", ctypes.c_int32 * 4), ("hmdStatus", ctypes.c_uint32), ("fsFixes", ctypes.c_uint32),
                ("hmdPos", ctypes.c_float * 3), ("hmdUp", ctypes.c_float * 3), ("ctlPosW", (ctypes.c_float * 3) * 2),
                ("madePrimary", ctypes.c_uint32), ("logSeq", ctypes.c_uint32), ("log", (ctypes.c_char * 96) * 8)]

class DriverLink:
    """Abre a memoria compartilhada do driver (existe so com o SteamVR aberto)."""
    def __init__(self):
        self.live = None
        self.k32 = None
        if os.name == "nt":
            self.k32 = ctypes.windll.kernel32
            self.k32.OpenFileMappingW.restype = ctypes.c_void_p
            self.k32.MapViewOfFile.restype = ctypes.c_void_p
            self.k32.MapViewOfFile.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_size_t]
    def try_open(self):
        if self.live is not None or self.k32 is None: return self.live is not None
        h = self.k32.OpenFileMappingW(0x0004 | 0x0002, False, "Local\\StormVRLive")     # FILE_MAP_READ | FILE_MAP_WRITE
        if not h: return False
        p = self.k32.MapViewOfFile(ctypes.c_void_p(h), 0x0004 | 0x0002, 0, 0, ctypes.sizeof(Live))
        if not p: return False
        self.live = Live.from_address(p)
        return True
    def alive(self):
        return self.live is not None and self.live.version != 0
    def command(self, idx):
        if self.try_open():
            self.live.cmd[idx] += 1
            return True
        return False

# --------------------------------------------------------------------------- interface
class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("StormVR — Painel (tempo real)")
        self.geometry("980x720"); self.minsize(820, 560)
        self.configure(bg="#10121c")
        st = ttk.Style(self)
        try: st.theme_use("clam")
        except tk.TclError: pass
        st.configure(".", background="#10121c", foreground="#e8ecf8", fieldbackground="#1c2034")
        st.configure("TNotebook", background="#10121c"); st.configure("TNotebook.Tab", background="#1c2034", foreground="#cdd5ee", padding=(14, 6))
        st.map("TNotebook.Tab", background=[("selected", "#2f4cc9")])
        st.configure("TFrame", background="#10121c"); st.configure("TLabel", background="#10121c", foreground="#e8ecf8")
        st.configure("TCheckbutton", background="#10121c", foreground="#e8ecf8")
        st.configure("TButton", background="#26305a", foreground="#ffffff")
        self.vals = {}
        saved = load_ini()
        cur = steamvr_current()
        self.touched = set(saved.keys())          # so gravamos no .ini o que o usuario mexeu (nao sobrescreve o resto com padroes)
        for (_t, key, kind, _l, dflt, mn, mx, _s) in SETTINGS:
            v = dflt
            if key in saved:
                try: v = (saved[key] not in ("0", "false")) if kind == "b" else float(saved[key])
                except ValueError: v = dflt
            elif key in cur and not isinstance(cur[key], str):
                v = bool(cur[key]) if kind == "b" else float(cur[key])
            self.vals[key] = v
        self.vars = {}
        self.pending = False
        self.link = DriverLink()
        self.status = tk.StringVar(value="")
        self.build()
        self.after(500, self.tick)

    # ---- construcao
    def build(self):
        top = ttk.Frame(self); top.pack(fill="x", padx=10, pady=(10, 0))
        ttk.Label(top, text="Mudanças aplicadas em TEMPO REAL no driver (SteamVR aberto) ou guardadas para quando abrir.", foreground="#9aa6d0").pack(side="left")
        nb = ttk.Notebook(self); nb.pack(fill="both", expand=True, padx=10, pady=8)
        tabs = []
        for t, *_ in SETTINGS:
            if t not in tabs: tabs.append(t)
        for t in tabs:
            frame = self.scrolled(nb); nb.add(frame.outer, text=t)
            for (tab, key, kind, label, dflt, mn, mx, step) in SETTINGS:
                if tab == t: self.add_row(frame.inner, key, kind, label, dflt, mn, mx, step)
        cmd = ttk.Frame(nb); nb.add(cmd, text="Comandos e estado")
        for i, (name, idx) in enumerate(COMMANDS):
            ttk.Button(cmd, text=name, command=lambda ix=idx: self.send_cmd(ix)).grid(row=i // 3, column=i % 3, padx=8, pady=8, sticky="ew")
        self.tele = tk.Text(cmd, height=22, bg="#0c0e18", fg="#cfe0ff", relief="flat", font=("Consolas", 10))
        self.tele.grid(row=3, column=0, columnspan=3, sticky="nsew", padx=8, pady=8)
        for c in range(3): cmd.columnconfigure(c, weight=1)
        cmd.rowconfigure(3, weight=1)
        bar = ttk.Frame(self); bar.pack(fill="x", padx=10, pady=(0, 10))
        ttk.Label(bar, textvariable=self.status, foreground="#9aa6d0").pack(side="left")
        ttk.Button(bar, text="Restaurar padrões", command=self.reset_all).pack(side="right")
        ttk.Button(bar, text="Resetar chaperone (SteamVR fechado)", command=self.reset_chaperone).pack(side="right", padx=8)

    def scrolled(self, parent):
        outer = ttk.Frame(parent)
        cv = tk.Canvas(outer, bg="#10121c", highlightthickness=0)
        sb = ttk.Scrollbar(outer, orient="vertical", command=cv.yview)
        inner = ttk.Frame(cv)
        inner.bind("<Configure>", lambda e: cv.configure(scrollregion=cv.bbox("all")))
        win = cv.create_window((0, 0), window=inner, anchor="nw")
        cv.bind("<Configure>", lambda e: cv.itemconfigure(win, width=e.width))
        cv.configure(yscrollcommand=sb.set)
        cv.pack(side="left", fill="both", expand=True); sb.pack(side="right", fill="y")
        cv.bind_all("<MouseWheel>", lambda e: cv.yview_scroll(int(-e.delta / 120), "units"))
        class R: pass
        r = R(); r.outer = outer; r.inner = inner
        return r

    def add_row(self, parent, key, kind, label, dflt, mn, mx, step):
        row = ttk.Frame(parent); row.pack(fill="x", padx=12, pady=5)
        if kind == "b":
            var = tk.BooleanVar(value=bool(self.vals[key])); self.vars[key] = var
            ttk.Checkbutton(row, text=label, variable=var, command=lambda: self.on_bool(key, var)).pack(side="left")
        elif kind == "c":
            ttk.Label(row, text=label, width=48).pack(side="left")
            var = tk.StringVar(value=CH[key][int(self.vals[key])]); self.vars[key] = var
            cb = ttk.Combobox(row, textvariable=var, values=CH[key], state="readonly", width=40); cb.pack(side="left")
            cb.bind("<<ComboboxSelected>>", lambda e: self.on_choice(key, var, cb))
        else:
            ttk.Label(row, text=label, width=48).pack(side="left")
            var = tk.DoubleVar(value=float(self.vals[key])); self.vars[key] = var
            val = ttk.Label(row, width=9, anchor="e"); val.pack(side="right")
            def upd(_=None, k=key, v=var, lab=val, kd=kind, st=step):
                x = v.get()
                x = round(x / st) * st if st else x
                lab.config(text=("%d" % round(x)) if kd == "i" else ("%.3f" % x).rstrip("0").rstrip("."))
                self.set_value(k, x)
            sc = ttk.Scale(row, from_=mn, to=mx, variable=var, command=upd); sc.pack(side="right", fill="x", expand=True, padx=8)
            upd()

    # ---- ajustes
    def set_value(self, key, v):
        if self.vals.get(key) == v: return
        self.vals[key] = v
        self.touched.add(key)
        self.schedule_save()

    def on_bool(self, key, var):
        v = bool(var.get())
        msg = RISKY.get((key, v))
        if msg and not messagebox.askyesno("StormVR — confirmar", msg, icon="warning", default="no"):
            var.set(not v); return
        self.set_value(key, v)

    def on_choice(self, key, var, cb):
        idx = CH[key].index(var.get())
        msg = RISKY.get((key, idx))
        if msg and not messagebox.askyesno("StormVR — confirmar", msg, icon="warning", default="no"):
            var.set(CH[key][int(self.vals[key])]); return
        self.set_value(key, idx)

    def schedule_save(self):
        if self.pending: return
        self.pending = True
        self.after(40, self.flush)          # junta os movimentos do slider: ~25 gravacoes/s no maximo

    def flush(self):
        self.pending = False
        try:
            save_ini(self.vals, self.touched)
            self.status.set("Aplicado %s" % time.strftime("%H:%M:%S"))
        except OSError as e:
            self.status.set("Erro ao gravar: %s" % e)

    def reset_all(self):
        if not messagebox.askyesno("StormVR — confirmar", "Restaurar TODAS as configurações para o padrão?", icon="warning", default="no"): return
        for (_t, key, kind, _l, dflt, mn, mx, _s) in SETTINGS:
            self.vals[key] = dflt; self.touched.add(key)
            v = self.vars.get(key)
            if v is None: continue
            if kind == "b": v.set(bool(dflt))
            elif kind == "c": v.set(CH[key][int(dflt)])
            else: v.set(float(dflt))
        self.flush()

    def reset_chaperone(self):
        if self.link.try_open() and self.link.alive():
            messagebox.showwarning("StormVR", "Feche o SteamVR primeiro (ele reescreve o arquivo ao fechar)."); return
        if not messagebox.askyesno("StormVR — confirmar", "Mover o chaperone_info.vrchap do SteamVR para um backup?\nA configuração de sala (Room Setup) volta ao padrão.", icon="warning", default="no"): return
        messagebox.showinfo("StormVR", chaperone_reset())

    def send_cmd(self, idx):
        if not self.link.command(idx): messagebox.showinfo("StormVR", "O driver não está rodando (abra o SteamVR).")

    # ---- telemetria
    def tick(self):
        try:
            self.link.try_open()
            t = self.tele
            t.delete("1.0", "end")
            t.insert("end", chaperone_report() + "\n\n")
            if not self.link.alive():
                t.insert("end", "Driver: SteamVR fechado (ou driver não carregado).\nOs ajustes ficam guardados em:\n%s\n\nO driver aplica tudo ao abrir." % INI)
            else:
                L = self.link.live
                t.insert("end", "Driver: ATIVO\n")
                t.insert("end", "Cabeça: %s  %.0f pacotes/s  ypr %.1f %.1f %.1f\n" % (["nunca recebeu", "recebendo", "SEM PACOTES"][min(L.headLink, 2)], L.headRate, *L.headYpr))
                t.insert("end", "Mãos: %s  %.0f pacotes/s\n" % (["nunca", "recebendo", "sem pacotes"][min(L.handLink, 2)], L.handRate))
                for h, nome in ((0, "Direita"), (1, "Esquerda")):
                    c = L.ctl[h]
                    t.insert("end", "Controle %s: %s | %s | %.0f pkt/s | rssi %d | %s | idade %.0f ms | perdidos %d\n" % (
                        nome, ["sem controle", "conectado", "calibrando"][min(c.link, 2)], ["?", "ESP32", "celular"][min(c.kind, 2)], c.pktRate, c.rssi,
                        "VR", c.ageMs if c.ageMs < 999999 else -1, c.lost))
                    t.insert("end", "   mpu %.1f %.1f %.1f  saída %.1f %.1f %.1f\n" % (*c.mpu, *c.out))
                # ---- diagnóstico de orientação (para achar "de cabeça pra baixo" com FATOS)
                up = list(L.hmdUp); hp = list(L.hmdPos)
                t.insert("end", "\nDIAGNÓSTICO VR\n")
                t.insert("end", "  Cabeça: 'cima' da cabeça no mundo = (%.2f, %.2f, %.2f)  → %s\n" % (up[0], up[1], up[2],
                         "OK (de pé)" if up[1] > 0.5 else ("DE CABEÇA PRA BAIXO (roll ≈ 180°)" if up[1] < -0.5 else "deitada/inclinada")))
                t.insert("end", "  Cabeça: posição (%.2f, %.2f, %.2f) m\n" % tuple(hp))
                for h, nome in ((0, "direita"), (1, "esquerda")):
                    cp = list(L.ctlPosW[h])
                    if any(abs(v) > 1e-6 for v in cp):
                        dy = cp[1] - hp[1]
                        t.insert("end", "  Mão %s: altura em relação à cabeça = %+.2f m  → %s\n" % (nome, dy, "OK (abaixo)" if dy < -0.05 else "ACIMA/ao nível da cabeça (suspeito)"))
                t.insert("end", "Janela do headset: %d,%d %dx%d  recolocada %d vezes\n" % (*L.hmdWin, L.fsFixes))
                t.insert("end", "\nDiagnóstico do driver:\n")
                for i in range(8):
                    s = bytes(L.log[i]).split(b"\0")[0].decode("utf-8", "replace")
                    if s: t.insert("end", "  " + s + "\n")
        except Exception as e:           # a telemetria nunca derruba o painel
            self.status.set("Telemetria: %s" % e)
        self.after(500, self.tick)

if __name__ == "__main__":
    App().mainloop()
