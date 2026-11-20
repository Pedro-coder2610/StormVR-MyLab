package com.stormvr.app.moonlight;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Preferências do Moonlight dentro do StormVR: PCs salvos, certificado de cada PC pareado, id do cliente e qualidade do vídeo. */
public final class MoonlightStore {
    private final SharedPreferences p;

    public MoonlightStore(Context c) { p = c.getSharedPreferences("stormvr_moonlight", Context.MODE_PRIVATE); }

    public List<String> hosts() {
        List<String> out = new ArrayList<>();
        String s = p.getString("hosts", "");
        for (String h : s.split(",")) if (!h.trim().isEmpty()) out.add(h.trim());
        return out;
    }
    public void addHost(String ip) {
        List<String> h = hosts();
        if (!h.contains(ip)) { h.add(ip); saveHosts(h); }
    }
    public void removeHost(String ip) {
        List<String> h = hosts(); h.remove(ip); saveHosts(h);
        p.edit().remove("cert_" + ip).apply();
    }
    private void saveHosts(List<String> h) { p.edit().putString("hosts", android.text.TextUtils.join(",", h)).apply(); }

    /** Identificador deste cliente (o Sunshine/GeForce vê como o "nome único" do aparelho). */
    public String uniqueId() {
        String id = p.getString("uid", null);
        if (id == null) {
            Random r = new Random();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(Integer.toHexString(r.nextInt(16)));
            id = sb.toString();
            p.edit().putString("uid", id).apply();
        }
        return id;
    }

    public void saveCert(String ip, X509Certificate cert) {
        try { p.edit().putString("cert_" + ip, Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP)).apply(); } catch (Exception ignored) {}
    }
    public X509Certificate cert(String ip) {
        String s = p.getString("cert_" + ip, null);
        if (s == null) return null;
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.decode(s, Base64.NO_WRAP)));
        } catch (Exception e) { return null; }
    }
    public boolean paired(String ip) { return cert(ip) != null; }

    // qualidade do vídeo
    public int width() { return p.getInt("w", 1920); }
    public int height() { return p.getInt("h", 1080); }
    public int fps() { return p.getInt("fps", 60); }
    public int bitrateKbps() { return p.getInt("kbps", 20000); }
    public void setResolution(int w, int h) { p.edit().putInt("w", w).putInt("h", h).apply(); }
    public void setFps(int f) { p.edit().putInt("fps", f).apply(); }
    public void setBitrateKbps(int k) { p.edit().putInt("kbps", k).apply(); }

    // TV da Sala 3D: qual app do PC abrir (nome no Sunshine: "Desktop", "Steam Big Picture", um monitor secundário configurado, etc.)
    // Proporção: false = mantém a proporção do stream (ex.: 16:9, com barras pretas); true = preenche toda a tela/painel (pode esticar).
    public boolean fill() { return p.getBoolean("fill", false); }
    public void setFill(boolean on) { p.edit().putBoolean("fill", on).apply(); }
    public String tvApp() { return p.getString("tvapp", "Desktop"); }
    public void setTvApp(String name) { p.edit().putString("tvapp", name).apply(); }
    // Áudio também no PC (padrão: SIM). Desligado, o som sai só no celular.
    // Ao abrir o Moonlight na Sala 3D: perguntar qual app abrir (padrão: SIM). Desligado, abre direto o app escolhido em tvApp().
    public boolean askApp() { return p.getBoolean("askapp", true); }
    public void setAskApp(boolean on) { p.edit().putBoolean("askapp", on).apply(); }
    public boolean localAudio() { return p.getBoolean("localaudio", true); }
    public void setLocalAudio(boolean on) { p.edit().putBoolean("localaudio", on).apply(); }
    // Modo do mouse na tela do celular: 0 = ABSOLUTO (o dedo e o cursor), 1 = TOUCHPAD (arrastar move o cursor; toque = clique; 2 dedos = rolagem),
    // 2 = TOQUE nativo (multitoque do Sunshine: o PC recebe toques de verdade; se o PC nao suportar, cai no absoluto).
    public int mouseMode() { return p.getInt("mousemode", 0); }
    public void setMouseMode(int m) { p.edit().putInt("mousemode", m).apply(); }
    // Telona (Sala 3D): modo de CADA controle (0 = MOUSE, 1 = TOQUE multitoque). "Mesmo modo nos dois" (padrao) usa o do controle 0 para ambos.
    // No modo MOUSE o GRIP e o botao DIREITO do mouse.
    public boolean ptrSame() { return p.getBoolean("ptrsame", true); }
    public void setPtrSame(boolean v) { p.edit().putBoolean("ptrsame", v).apply(); }
    public int ptrMode(int k) { return p.getInt("ptrmode" + (k == 0 ? 0 : 1), 0); }
    public void setPtrMode(int k, int m) { p.edit().putInt("ptrmode" + (k == 0 ? 0 : 1), m).apply(); }
    // Gamepad do celular -> PC (Xbox virtual) na tela cheia do Moonlight: ligado por padrao; duplo clique no Start liga/desliga.
    public boolean padToPc() { return p.getBoolean("padtopc", true); }
    public void setPadToPc(boolean on) { p.edit().putBoolean("padtopc", on).apply(); }
}
