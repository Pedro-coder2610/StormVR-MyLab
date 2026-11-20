# -*- coding: utf-8 -*-
"""Gera manifest.json (abas, controles, rotulos, limites e padroes) a partir de ../common/common.h e ../config/config_app.cpp.
Rode:  py gen_manifest.py     (o stormvr_web.py usa o manifest.json que ja vem pronto; so precisa rodar de novo se mudar o driver)"""
import re, json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
H = open(os.path.join(HERE, "..", "common", "common.h"), encoding="utf-8").read()
C = open(os.path.join(HERE, "..", "config", "config_app.cpp"), encoding="utf-8").read()
def strip(s): return re.sub(r"/\*.*?\*/", "", re.sub(r"//[^\n]*", "", s), flags=re.S)
Hs = strip(H)

# ---- enums (para padroes que sao nomes)
ENUM = {}
for m in re.finditer(r"enum\s*(?:class\s+\w+|\w+)?\s*\{([^}]*)\}", Hs):
    v = 0
    for item in m.group(1).split(","):
        item = item.strip()
        if not item: continue
        if "=" in item:
            n, e = [x.strip() for x in item.split("=", 1)]
            try: v = int(e, 0)
            except ValueError: v = ENUM.get(e, v)
        else: n = item
        ENUM[n] = v; v += 1
ENUM.update({"M_ROLL": 0, "M_PITCH": 1, "M_YAW": 2})

def num(tok):
    t = tok.strip().rstrip("fF") if re.match(r"^-?[0-9.]+[fF]?$", tok.strip()) else tok.strip()
    if t in ("true", "false"): return t == "true"
    try: return float(t) if "." in t else int(t, 0)
    except ValueError: return ENUM.get(t, 0)

def parse_braces(s, i=0):
    """parse '{ ... }' -> lista aninhada; folha = ('key', [campos])"""
    assert s[i] == "{"
    i += 1; items = []; cur = ""
    while i < len(s):
        ch = s[i]
        if ch == "{":
            sub, i = parse_braces(s, i); items.append(sub); cur = ""; continue
        if ch == "}":
            if cur.strip(): items.append(cur.strip())
            i += 1; break
        if ch == ",":
            if cur.strip(): items.append(cur.strip())
            cur = ""; i += 1; continue
        cur += ch; i += 1
    # folha?
    if items and isinstance(items[0], str) and re.match(r'^"[^"]*"', items[0]):
        return ("leaf", items), i
    return items, i

DECL = {}
for m in re.finditer(r"inline\s+const\s+(FloatSetting|IntSetting|BoolSetting)\s+(\w+)((?:\[[^\]]*\])*)\s*=\s*", Hs):
    typ, name = m.group(1), m.group(2)
    j = m.end()
    if Hs[j] == "{":
        tree, _ = parse_braces(Hs, j)
    else:
        raise SystemExit("decl inesperada " + name)
    DECL[name] = (typ, tree)

def leaf_to_setting(typ, leaf, suf=""):
    f = leaf[1]
    key = re.match(r'^"([^"]*)"', f[0]).group(1) + suf
    rest = f[1:]
    if typ == "BoolSetting": return {"key": key, "type": "b", "def": bool(num(rest[0])) if rest else False}
    d, mn, mx = [num(x) for x in rest[:3]]
    if typ == "IntSetting": return {"key": key, "type": "i", "def": int(d), "min": int(mn), "max": int(mx)}
    return {"key": key, "type": "f", "def": float(d), "min": float(mn), "max": float(mx)}

def walk(typ, tree, suf=""):
    if isinstance(tree, tuple): return leaf_to_setting(typ, tree, suf)
    return [walk(typ, t, suf) for t in tree]

S = {}
for name, (typ, tree) in DECL.items(): S[name] = walk(typ, tree)
# chaves "soltas" (booleanos so com a chave): constexpr const char* kX = "chave";  ou  kX[N] = { "a", "b" };
for m in re.finditer(r'constexpr\s+const\s+char\*\s+(k\w+)((?:\[[^\]]*\])?)\s*=\s*(\{[^}]*\}|"[^"]*")\s*;', Hs):
    name, arr, val = m.group(1), m.group(2), m.group(3)
    ks = re.findall(r'"([^"]*)"', val)
    if name in ("kSection",): continue
    S[name] = [{"key": k, "type": "b", "def": True} for k in ks] if arr else {"key": ks[0], "type": "b", "def": True}

# ---- kCtl (macro por mao)
mac = re.search(r"#define\s+KBT_CTL_SETTINGS\(SUF\)\s*\{(.*?)\n\s*inline const CtlSettings", H, re.S)
body = strip(mac.group(1)).replace("\\\n", " ")
body = "{" + body.strip().rstrip("}").strip() + "}"      # reconstitui a lista externa
body = body.replace('" SUF', '"').replace('"SUF', '"')
tree, _ = parse_braces("{" + body[1:-1] + "}")
FIELDS = [("axSrc", "IntSetting"), ("axEn", "BoolSetting"), ("axInv", "BoolSetting"), ("axGain", "FloatSetting"), ("axSmooth", "FloatSetting"),
          ("mpuDz", "FloatSetting"), ("stickDz", "FloatSetting"), ("stickInv", "BoolSetting"), ("stickSwap", "BoolSetting"), ("stickGamma", "FloatSetting"),
          ("stickEdge", "FloatSetting"), ("stickSens", "FloatSetting"), ("trigRamp", "FloatSetting"), ("gripRamp", "FloatSetting"),
          ("clickThr", "FloatSetting"), ("yawDrift", "FloatSetting")]
CTL = [{}, {}]
for h, suf in enumerate(["", "_left"]):
    for (fname, typ), sub in zip(FIELDS, tree):
        CTL[h][fname] = walk(typ, sub, suf)

def get(expr):
    m = re.match(r"^(\w+)((?:\.\w+|\[\d+\])*)$", expr)
    name = m.group(1)
    if name == "kCtl":
        rest = m.group(2); h = int(re.match(r"\[(\d)\]", rest).group(1)); rest = rest[3:]
        cur = CTL[h]
        for tok in re.findall(r"\.(\w+)|\[(\d+)\]", rest):
            cur = cur[tok[0]] if tok[0] else cur[int(tok[1])]
        return cur
    cur = S[name]
    for idx in re.findall(r"\[(\d+)\]", m.group(2) or ""): cur = cur[int(idx)]
    return cur

# ---- opcoes dos ciclos (vetores de texto em config_app.cpp)
OPT = {}
for m in re.finditer(r"static\s+const\s+std::vector<std::wstring>\s+(kOpt\w+)\s*=\s*\{(.*?)\};", C, re.S):
    OPT[m.group(1)] = re.findall(r'L"((?:[^"\\]|\\.)*)"', m.group(2))
PHYS = re.findall(r'L"((?:[^"\\]|\\.)*)"', (re.search(r"kPhysName\s*\[[^\]]*\]\s*=\s*\{(.*?)\};", C, re.S) or re.search(r"kPhysName\s*\[[^\]]*\]\s*=\s*\{(.*?)\};", H, re.S)).group(1)) if (re.search(r"kPhysName\s*\[[^\]]*\]\s*=\s*\{(.*?)\};", C, re.S) or re.search(r"kPhysName\s*\[[^\]]*\]\s*=\s*\{(.*?)\};", H, re.S)) else []

def sl(label, expr, step=None):
    s = get(expr); w = {"t": "slider", "label": label, "key": s["key"], "min": s["min"], "max": s["max"], "def": s["def"]}
    if step: w["step"] = step
    elif s["type"] == "i": w["step"] = 1
    else:
        rng = s["max"] - s["min"]; w["step"] = 1 if rng >= 300 else (0.1 if rng >= 40 else (0.01 if rng >= 3 else 0.001))
    w["int"] = s["type"] == "i"; return w
def ck(label, expr):
    s = get(expr); return {"t": "check", "label": label, "key": s["key"], "def": s["def"]}
def cy(label, expr, opt):
    s = get(expr); return {"t": "cycle", "label": label, "key": s["key"], "opts": OPT.get(opt, []), "def": s["def"], "min": s.get("min", 0), "max": s.get("max", len(OPT.get(opt, [])) - 1)}
def bt(label, cmd): return {"t": "button", "label": label, "cmd": cmd}
def gr(title): return {"t": "group", "title": title}

HANDN = ["Direito", "Esquerdo"]
tabs = []
tabs.append(("Geral", [
    ck("MODO TV: monitor como headset, parado e sem estéreo (reinicie o SteamVR)", "kHmdMonitor"),
    ck("Zerar os eixos dos controles ao conectar (corrige \"de cabeça pra baixo\")", "kAutoZeroConn"),
    ck("Teclado ativo", "kKeysEnabled"),
    ck("Controle direito conectado", "kEnabled[0]"), ck("Controle esquerdo conectado", "kEnabled[1]"),
    bt("Recentralizar a cabeça", 4), bt("Recentralizar controle esquerdo", 11), bt("Recentralizar controle direito", 10),
    ck("Girar a cabeça 180° (se o mundo está de cabeça pra baixo)", "kHeadFlip"),
    bt("Resetar piso / espaço de jogo do SteamVR", 12), bt("Reiniciar o SteamVR", 13),
    sl("Previsão de movimento dos controles (ms)", "kPredictMs"),
    ck("Controles por quaternion (corrige apontar p/ cima e p/ baixo)", "kCtlQuat"),
    sl("Suavização dos controles (%)", "kCtlSmooth"),
    ck("Controles seguem o deslocamento do headset", "kFollowHmd"), ck("Girar também com a direção do headset", "kFollowHmdRot"),
    ck("Abrir o painel WEB junto com o SteamVR", "kWebAutostart"),
    sl("Velocidade de movimento (m/s)", "kMoveSpeed"), sl("Velocidade de rotação (°/s)", "kRotSpeed"), sl("Altura no modo fixo (m)", "kFixedHeight"),
    gr("Posição dos controles (relativa à cabeça)"),
] + [sl("%s — %s" % (HANDN[h], ax), "kOffset[%d][%d]" % (h, i)) for h in range(2) for i, ax in enumerate(["X: esquerda ↔ direita", "Y: altura", "Z: profundidade (− = à frente)"])]
  + [bt("Resetar posições", "reset_pos"), bt("Restaurar tudo", "reset_all")]))
tabs.append(("Controles", [
    cy("Quem fica em cada mão", "kAssign", "kOptAssign"), cy("Controle usa", "kSrcMode", "kOptSrc"),
    ck("Trocar mãos (direita ↔ esquerda)", "kSwap"), ck("Recentrar yaw ao conectar", "kAutoCenter"),
    ck("Combo L3 + Start recentra yaw", "kComboRecenter"), ck("Esconder o controle se ele cair", "kHideLost"),
    sl("Sem pacotes por quanto tempo = desconectado (s)", "kEspTimeout"),
    bt("Recentrar yaw", 0), bt("Zerar yaw/pitch/roll", 1), bt("Calibrar giroscópios", 2),
    gr("Mãos rastreadas pela câmera do celular"),
    ck("Posição da mão dir.", "kHandsPos[0]"), ck("Pinça = trigger dir.", "kHandsPinch[0]"), ck("Direção da mão dir.", "kHandsAim[0]"),
    ck("Posição da mão esq.", "kHandsPos[1]"), ck("Pinça = trigger esq.", "kHandsPinch[1]"), ck("Direção da mão esq.", "kHandsAim[1]"),
    cy("Canal do ESP32", "kEspChannel", "kOptChannel"), sl("Suavização da posição das mãos rastreadas (%)", "kHandsSmooth"),
]))
tabs.append(("Headset", [
    bt("Recentrar cabeça", 4), ck("Forçar tela cheia (-202)", "kForceFs"), ck("Tornar principal ao abrir o SteamVR", "kMakePrimary"),
    cy("Resolução do monitor", "kHmdResMode", "kOptRes"), cy("Taxa de atualização", "kHmdHzMode", "kOptHz"), cy("Rastreamento", "kTrackMode", "kOptTrack"),
    sl("Altura do usuário (m)", "kUserHeight"), sl("Ajuste de altura (m)", "kHeadHeightOff"),
    sl("Suavização da cabeça (%)", "kHeadSmooth"), sl("Previsão da cabeça (ms)", "kHeadPredictMs"),
    sl("Girar o mundo (°)", "kWorldYaw"), sl("Rolagem da imagem (°)", "kHeadRoll"),
    sl("FOV horizontal (°)", "kHmdFovH"), sl("FOV vertical (°)", "kHmdFovV"), sl("Distância entre os olhos / IPD (m)", "kHmdIpd"),
    sl("Distância entre as 2 imagens (%)", "kHmdSep"), sl("Distorção k1", "kHmdK1"), sl("Distorção k2", "kHmdK2"), sl("Escala de renderização", "kHmdRenderScale"),
]))
eix = []
for h in range(2):
    eix.append(gr("Controle %s" % HANDN[h].upper()))
    for a, nm in enumerate(["Yaw", "Pitch", "Roll"]):
        eix += [cy("%s ←" % nm, "kCtl[%d].axSrc[%d]" % (h, a), "kOptMpu"), ck("%s: ativo" % nm, "kCtl[%d].axEn[%d]" % (h, a)), ck("%s: inverter" % nm, "kCtl[%d].axInv[%d]" % (h, a)),
                sl("Ganho — %s" % nm, "kCtl[%d].axGain[%d]" % (h, a)), sl("Suavização (ms) — %s" % nm, "kCtl[%d].axSmooth[%d]" % (h, a))]
    for i, nm in enumerate(["Roll (X)", "Pitch (Y)", "Yaw (Z)"]): eix.append(sl("Zona morta MPU %s" % nm, "kCtl[%d].mpuDz[%d]" % (h, i)))
    eix.append(sl("Deriva do yaw (°/min)", "kCtl[%d].yawDrift" % h))
eix += [bt("Recentrar yaw", 0), bt("Zerar yaw/pitch/roll", 1), bt("Calibrar giroscópio", 2)]
tabs.append(("Eixos", eix))
an = []
for h in range(2):
    an += [gr("Controle %s" % HANDN[h].upper()), sl("Zona morta do eixo X", "kCtl[%d].stickDz[0]" % h), sl("Zona morta do eixo Y", "kCtl[%d].stickDz[1]" % h),
           sl("Sensibilidade (ganho da saída)", "kCtl[%d].stickSens" % h), sl("Curva de resposta (1 = linear)", "kCtl[%d].stickGamma" % h), sl("Borda: fração do curso = 100%", "kCtl[%d].stickEdge" % h),
           ck("Inverter X (VRx)", "kCtl[%d].stickInv[0]" % h), ck("Inverter Y (VRy)", "kCtl[%d].stickInv[1]" % h), ck("Trocar eixos X ↔ Y", "kCtl[%d].stickSwap" % h),
           sl("Rampa do Trigger (1/s)", "kCtl[%d].trigRamp" % h), sl("Rampa do Grip (1/s)", "kCtl[%d].gripRamp" % h)]
an.append(bt("Calibrar centro (solte o analógico)", 3))
tabs.append(("Analógico", an))
pf = [cy("Perfil do controle (trocar exige reiniciar o SteamVR)", "kProfile", "kOptProfile"),
      sl("Limiar de clique do Trigger/Grip — direito", "kCtl[0].clickThr"), sl("Limiar de clique do Trigger/Grip — esquerdo", "kCtl[1].clickThr")]
for p, pn in enumerate(["Oculus", "Vive", "Index", "Genérico", "Frame"]):
    for i, nm in enumerate(["inclinar (pitch, °)", "girar (yaw, °)", "rolar (roll, °)"]): pf.append(sl("%s — rotação do controle: %s" % (pn, nm), "kRotOff[%d][%d]" % (p, i)))
tabs.append(("Perfis", pf))
tabs.append(("Arm Model", [
    ck("Ativar modelo de braço virtual (estimativa)", "kArmEnabled"), cy("Modo", "kArmMode", "kOptArmMode"),
    sl("Modo simples: comprimento do cabo / raio da esfera (m)", "kArmSimpleLen"),
    sl("Modo simples: cotovelo (centro da esfera) abaixo do ombro (m)", "kArmSimpleElbow"),
    sl("Braço: ombro→cotovelo (m)", "kArmUpper"), sl("Antebraço (m)", "kArmFore"), sl("Ombro lateral (m)", "kArmShX"), sl("Ombro abaixo (m)", "kArmShDrop"),
    sl("Alcance geral", "kArmReach"), sl("Mão ↓: cotovelo à frente", "kArmBendDown"), sl("Mão ↑: cotovelo atrás", "kArmBendUp"),
    sl("Alcance extra (lados)", "kArmSideReach"), sl("Giro do tronco", "kArmTorso"),
    ck("Prévia manual (sem ESP32)", "kPrevManual"), sl("Prévia: pitch (°)", "kPrevPitch"), sl("Prévia: yaw (°)", "kPrevYaw"),
    bt("Restaurar Arm Model", "reset_arm"),
]))
co = [sl("CABEÇA posição %s (m)" % a, "kHeadOff[%d]" % i) for i, a in enumerate(["X (direita +)", "Y (cima +)", "Z (trás +)"])]
co += [sl("CABEÇA rotação %s" % a, "kHeadRot[%d]" % i) for i, a in enumerate(["Inclinar (pitch °)", "Girar (yaw °)", "Rolar (roll °)"])]
for h in range(2): co += [sl("CONTROLE %s %s (m)" % (HANDN[h].upper(), a), "kCtlOff[%d][%d]" % (h, i)) for i, a in enumerate(["X (direita +)", "Y (cima +)", "Z (trás +)"])]
co.append(bt("Zerar todas as coordenadas", "reset_coord"))
tabs.append(("Coordenadas", co))
# Botoes (mapeamento): uma lista de selects por controle
btn = []
nb = len(PHYS)
for h in range(2):
    btn.append(gr("Controle %s — o que cada botão faz" % HANDN[h].upper()))
    for pb in range(nb):
        try:
            s = get("kBind[%d][%d]" % (h, pb)); sf = get("kBindFrame[%d][%d]" % (h, pb))
            nm = s["key"].split("_", 2)[2] if s["key"].count("_") >= 2 else s["key"]
            lab = {"trigger": "Trigger", "grip": "Grip", "a": "Botão A", "b": "Botão B", "x": "Botão X", "y": "Botão Y", "menu": "Menu", "system": "Sistema",
                   "stick_click": "Clique do analógico", "stick_up": "Analógico ↑", "stick_down": "Analógico ↓", "stick_left": "Analógico ←", "stick_right": "Analógico →"}.get(nm, nm.replace("_", " ").title())
            btn.append({"t": "cycle", "label": lab, "key": s["key"], "keyFrame": sf["key"], "optset": "kOptOut", "def": s["def"], "min": 0, "max": len(OPT.get("kOptOut", [])) - 1})
        except Exception: break
tabs.append(("Botões", btn))

def clean(w):
    return w
manifest = {"optsets": {"kOptOut": OPT.get("kOptOut", [])}, "tabs": [{"name": n, "widgets": [w for w in ws if w]} for n, ws in tabs]}
# todas as chaves (para o driver publicar o estado atual)
keys = {}
def collect(o):
    if isinstance(o, dict):
        if "key" in o and "type" in o and "def" in o: keys[o["key"]] = o["type"]
        for v in o.values(): collect(v)
    elif isinstance(o, list):
        for v in o: collect(v)
collect(S); collect(CTL)
manifest["types"] = keys
defaults = {}
def coll_def(o):
    if isinstance(o, dict):
        if "key" in o and "type" in o and "def" in o: defaults[o["key"]] = o["def"]
        for v in o.values(): coll_def(v)
    elif isinstance(o, list):
        for v in o: coll_def(v)
coll_def(S); coll_def(CTL)
manifest["defaults"] = defaults
json.dump(manifest, open(os.path.join(HERE, "manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("manifest.json:", len(manifest["tabs"]), "abas,", sum(len(t["widgets"]) for t in manifest["tabs"]), "controles,", len(keys), "chaves")
