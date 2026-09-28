# Тестовый DLNA MediaRenderer для проверки «Воспроизвести на…» без телевизора.
# SSDP (ответ на M-SEARCH), описание устройства, SOAP AVTransport/RenderingControl/ConnectionManager.
# «Воспроизведение»: реально скачивает поток по выданному URI (как телевизор), считает байты,
# проверяет синхробайт MPEG-TS 0x47, ведёт часы позиции. Журнал — в renderer.log.
#
# Запуск: python scripts/dlna-test-renderer.py <IPv4 ПК в сети телефона> [файл журнала]
# Телефон и ПК — в одной сети (Wi‑Fi или USB-модем телефона). Состояние: http://<IP>:8123/state
import socket, struct, threading, time, re, sys, http.server, urllib.request, html, json

IFACE = sys.argv[1] if len(sys.argv) > 1 else "10.248.44.116"
PORT = 8123
UDN = "uuid:vetro-test-renderer-0001"
LOG = open(sys.argv[2] if len(sys.argv) > 2 else "renderer.log", "a", encoding="utf-8", buffering=1)
SINK = ",".join([
    "http-get:*:video/mp4:*", "http-get:*:video/mpeg:*", "http-get:*:video/vnd.dlna.mpeg-tts:*",
    "http-get:*:text/srt:*", "http-get:*:audio/mpeg:*",
])

def log(*a):
    line = time.strftime("%H:%M:%S ") + " ".join(str(x) for x in a)
    print(line, flush=True); LOG.write(line + "\n")

state = {"uri": None, "meta": "", "status": "NO_MEDIA_PRESENT", "pos": 0.0, "anchor": None, "dur": 0.0,
         "bytes": 0, "ts_ok": None, "volume": 30, "fetch": None, "requests": []}
lock = threading.RLock()

def position():
    with lock:
        if state["status"] == "PLAYING" and state["anchor"] is not None:
            return state["pos"] + (time.time() - state["anchor"])
        return state["pos"]

def fmt(sec):
    sec = max(0, int(sec)); return "%d:%02d:%02d" % (sec // 3600, sec % 3600 // 60, sec % 60)

def parse_t(v):
    h, m, s = v.split(":"); return int(h) * 3600 + int(m) * 60 + float(s)

def fetcher(uri, gen):
    # Как ТВ: HEAD не у всех, сразу GET; для mp4 — пробуем Range с начала.
    try:
        req = urllib.request.Request(uri, headers={"User-Agent": "VetroTestRenderer/1.0", "Range": "bytes=0-"})
        with urllib.request.urlopen(req, timeout=20) as r:
            ctype = r.headers.get("Content-Type"); code = r.status
            log("FETCH start", code, ctype, "len=", r.headers.get("Content-Length"), "range=", r.headers.get("Content-Range"),
                "dlna=", r.headers.get("contentFeatures.dlna.org"))
            first = r.read(188 * 4)
            with lock:
                state["ts_ok"] = (len(first) >= 188 and first[0] == 0x47 and first[188] == 0x47) if "mp2t" in (ctype or "") or "mpeg" in (ctype or "") else None
                state["bytes"] += len(first)
            log("FETCH first bytes", first[:8].hex(), "ts_sync_ok=", state["ts_ok"])
            while True:
                with lock:
                    if state["fetch"] != gen: log("FETCH cancelled (new uri/stop)"); return
                chunk = r.read(256 * 1024)
                if not chunk: break
                with lock: state["bytes"] += len(chunk)
                # Держим темп «воспроизведения»: не больше ~6 МБ вперёд.
                while True:
                    with lock:
                        if state["fetch"] != gen: return
                        paused = state["status"] != "PLAYING"
                    if state["bytes"] < 6_000_000 + position() * 400_000 and not paused: break
                    time.sleep(0.2)
        log("FETCH done bytes=", state["bytes"])
    except Exception as e:
        log("FETCH error", type(e).__name__, e)

# ---------- SSDP ----------
def ssdp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", 1900))
    mreq = struct.pack("4s4s", socket.inet_aton("239.255.255.250"), socket.inet_aton(IFACE))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    log("SSDP listening on", IFACE)
    while True:
        data, addr = s.recvfrom(4096)
        text = data.decode(errors="ignore")
        if not text.startswith("M-SEARCH"): continue
        st = re.search(r"(?im)^ST:\s*(.+)$", text)
        st = st.group(1).strip() if st else ""
        if st not in ("ssdp:all", "upnp:rootdevice", "urn:schemas-upnp-org:device:MediaRenderer:1",
                      "urn:schemas-upnp-org:service:AVTransport:1", UDN):
            continue
        log("SSDP M-SEARCH from", addr, "ST=", st)
        resp = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
                f"LOCATION: http://{IFACE}:{PORT}/desc.xml\r\nSERVER: Windows/10 UPnP/1.0 VetroTest/1.0\r\n"
                f"ST: {st}\r\nUSN: {UDN}::{st}\r\n\r\n")
        s.sendto(resp.encode(), addr)

DESC = f"""<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Vetro Test TV</friendlyName><manufacturer>VetroTest</manufacturer><modelName>Test Renderer TV</modelName>
<UDN>{UDN}</UDN><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><controlURL>/ctl/avt</controlURL><eventSubURL>/evt/avt</eventSubURL><SCPDURL>/scpd/avt.xml</SCPDURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><controlURL>/ctl/rc</controlURL><eventSubURL>/evt/rc</eventSubURL><SCPDURL>/scpd/rc.xml</SCPDURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><controlURL>/ctl/cm</controlURL><eventSubURL>/evt/cm</eventSubURL><SCPDURL>/scpd/cm.xml</SCPDURL></service>
</serviceList></device></root>"""

def soap_resp(service, action, values):
    body = "".join(f"<{k}>{html.escape(str(v))}</{k}>" for k, v in values.items())
    return (f'<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            f's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action}Response xmlns:u="urn:schemas-upnp-org:service:{service}:1">{body}</u:{action}Response></s:Body></s:Envelope>')

def arg(xml, name):
    m = re.search(rf"<{name}>(.*?)</{name}>", xml, re.S); return html.unescape(m.group(1)) if m else None

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path == "/desc.xml":
            b = DESC.encode(); self.send_response(200); self.send_header("Content-Type", "text/xml"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
            log("DESC served to", self.client_address[0])
        elif self.path == "/state":
            with lock: s = {k: v for k, v in state.items() if k not in ("fetch",)}
            s["position"] = position(); b = json.dumps(s, ensure_ascii=False).encode()
            self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
        else:
            self.send_response(404); self.end_headers()
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); xml = self.rfile.read(n).decode(errors="ignore")
        action = (self.headers.get("SOAPACTION") or "").strip('"').split("#")[-1]
        svc = {"/ctl/avt": "AVTransport", "/ctl/rc": "RenderingControl", "/ctl/cm": "ConnectionManager"}.get(self.path, "?")
        values = {}
        with lock:
            if action == "SetAVTransportURI":
                state["uri"] = arg(xml, "CurrentURI"); state["meta"] = arg(xml, "CurrentURIMetaData") or ""
                state["status"] = "STOPPED"; state["pos"] = 0.0; state["anchor"] = None; state["bytes"] = 0; state["ts_ok"] = None
                state["fetch"] = object()
                title = re.search(r"<dc:title>(.*?)</dc:title>", state["meta"]); proto = re.search(r'protocolInfo="([^"]+)"', state["meta"])
                caption = re.search(r"<sec:CaptionInfoEx[^>]*>(.*?)</sec:CaptionInfoEx>", state["meta"])
                log("SOAP SetAVTransportURI uri=", state["uri"], "| title=", title.group(1) if title else None,
                    "| protocolInfo=", proto.group(1) if proto else None, "| caption=", caption.group(1) if caption else None)
            elif action == "Play":
                if state["status"] != "PLAYING":
                    state["status"] = "PLAYING"; state["anchor"] = time.time()
                    if state["uri"] and state["bytes"] == 0:
                        gen = state["fetch"]; threading.Thread(target=fetcher, args=(state["uri"], gen), daemon=True).start()
                log("SOAP Play")
            elif action == "Pause":
                state["pos"] = position(); state["anchor"] = None; state["status"] = "PAUSED_PLAYBACK"; log("SOAP Pause at", fmt(state["pos"]))
            elif action == "Stop":
                state["pos"] = 0.0; state["anchor"] = None; state["status"] = "STOPPED"; state["fetch"] = None; log("SOAP Stop")
            elif action == "Seek":
                target = parse_t(arg(xml, "Target")); state["pos"] = target
                if state["status"] == "PLAYING": state["anchor"] = time.time()
                log("SOAP Seek", arg(xml, "Unit"), arg(xml, "Target"))
            elif action == "GetTransportInfo":
                values = {"CurrentTransportState": state["status"], "CurrentTransportStatus": "OK", "CurrentSpeed": "1"}
            elif action == "GetPositionInfo":
                p = (state["pos"] + (time.time() - state["anchor"])) if state["status"] == "PLAYING" and state["anchor"] else state["pos"]
                values = {"Track": 1, "TrackDuration": fmt(1440), "TrackMetaData": "", "TrackURI": state["uri"] or "",
                          "RelTime": fmt(p), "AbsTime": fmt(p), "RelCount": 2147483647, "AbsCount": 2147483647}
            elif action == "GetProtocolInfo":
                values = {"Source": "", "Sink": SINK}; log("SOAP GetProtocolInfo from", self.client_address[0])
            elif action == "SetVolume":
                state["volume"] = int(arg(xml, "DesiredVolume") or 0); log("SOAP SetVolume", state["volume"])
            elif action == "GetVolume":
                values = {"CurrentVolume": state["volume"]}
            else:
                log("SOAP unknown", svc, action)
        b = soap_resp(svc, action, values).encode()
        self.send_response(200); self.send_header("Content-Type", 'text/xml; charset="utf-8"'); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

threading.Thread(target=ssdp, daemon=True).start()
srv = http.server.ThreadingHTTPServer((IFACE, PORT), H)
log("HTTP on", IFACE, PORT)
srv.serve_forever()
