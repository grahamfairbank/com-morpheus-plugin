#!/usr/bin/env python3
"""Draws docs/images/how-it-works.svg, the "How it works" infographic in the README.

    python3 tools/draw-how-it-works.py

Edit the text below and run it again; the layout (wrapping, card heights) is computed.
"""
from html import escape
from pathlib import Path

W = 1400
FONT = "-apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"
INK, MUTED, LINE = "#1b1f24", "#57606a", "#d0d7de"
BLUE = ("#eaf2fc", "#2a6bb8")     # browser
TEAL = ("#ecf6f2", "#1c7a5c")     # Morpheus appliance / plugin
GREEN = ("#eef7e6", "#3f7a1d")    # GreenLake
AMBER = ("#fff5e1", "#a35f00")    # security gates
GRAY = ("#f6f8fa", "#57606a")

out: list[str] = []
MARKERS: set[str] = set()


def t(x, y, s, size=14, weight="normal", fill=INK, anchor="start", italic=False):
    st = ' font-style="italic"' if italic else ""
    out.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{fill}" '
               f'text-anchor="{anchor}"{st}>{escape(s)}</text>')


def rect(x, y, w, h, fill, stroke, rx=10, sw=1.5, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>')


def wrap(s, width, size):
    per = max(8, int(width / (size * 0.53)))
    words, lines, cur = s.split(), [], ""
    for w in words:
        if cur and len(cur) + 1 + len(w) > per:
            lines.append(cur)
            cur = w
        else:
            cur = f"{cur} {w}".strip()
    if cur:
        lines.append(cur)
    return lines


def para(x, y, w, s, size=13, fill=INK, gap=4, bullet=None, weight="normal"):
    """Writes wrapped text; returns the y below it."""
    indent = 14 if bullet else 0
    lines = wrap(s, w - indent, size)
    for i, ln in enumerate(lines):
        if i == 0 and bullet:
            t(x, y + size, bullet, size, "bold", fill)
        t(x + indent, y + size, ln, size, weight, fill)
        y += size + gap
    return y


def card(x, y, w, title, items, colors, size=13, title_size=15, bullet="•", draw=True):
    """A rounded card with a title and wrapped bullet items; returns its height."""
    global out
    saved = out
    out = []
    cy = y + 14
    t(x + 14, cy + title_size, title, title_size, "bold", colors[1])
    cy += title_size + 10
    for it in items:
        if isinstance(it, tuple):           # (text, style)
            text, style = it
            if style == "note":
                cy = para(x + 14, cy, w - 28, text, size - 1, MUTED) + 4
            elif style == "plain":
                cy = para(x + 14, cy, w - 28, text, size) + 4
            elif style == "code":
                cy = para(x + 14, cy, w - 28, text, size - 1, colors[1], weight="bold") + 4
            else:                           # custom bullet
                cy = para(x + 14, cy, w - 28, text, size, bullet=style) + 4
        else:
            cy = para(x + 14, cy, w - 28, it, size, bullet=bullet) + 4
    h = cy - y + 8
    body = out
    out = saved
    if draw:
        rect(x, y, w, h, "#ffffff", colors[1], rx=8, sw=1.2)
        out.extend(body)
    return h


def arrow(x1, y1, x2, y2, color=INK, label=None, lx=None, ly=None, both=False):
    mid = "a" + color.lstrip("#")
    MARKERS.add(color)
    m = f' marker-start="url(#{mid})"' if both else ""
    out.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="2" marker-end="url(#{mid})"{m}/>')
    if label:
        for i, ln in enumerate(label.split("\n")):
            t(lx if lx is not None else (x1 + x2) / 2, (ly if ly is not None else (y1 + y2) / 2 - 8) + i * 15,
              ln, 12, "bold", color, "middle")


def zone(x, y, w, h, title, sub, colors):
    rect(x, y, w, h, colors[0], colors[1], rx=14, sw=2)
    t(x + 18, y + 30, title, 19, "bold", colors[1])
    t(x + 18, y + 50, sub, 13, "normal", MUTED)


def stack(x, y, w, cards, gap=12):
    for title, items, colors in cards:
        y += card(x, y, w, title, items, colors) + gap
    return y


# ---------------------------------------------------------------- architecture
TOP = 108
COL1_X, COL1_W = 24, 306
COL2_X, COL2_W = 400, 600
COL3_X, COL3_W = 1070, 306

col1 = [
    ("Host page › HPE COM tab", [
        "Seven sub-tabs: Summary, Health, Firmware, Hardware, Storage, Support, Alerts",
        "Links: Open in COM, Open iLO",
    ], BLUE),
    ("Cluster page › HPE COM tab", [
        ("New in 0.4.0", "code"),
        "One row per host: COM match, health, power, active alerts, support, COM maintenance",
        "Totals across the cluster",
    ], BLUE),
    ("Action buttons (optional)", [
        "UID light on / off",
        "Collect inventory",
        "iLO reset (asks to confirm)",
        "Enter / Exit COM maintenance (asks to confirm)",
        ("Each click is a POST that carries Morpheus's CSRF token, X-Requested-With and the tab's signed action token. Job progress shows live.", "note"),
    ], BLUE),
]
col2_left = [
    ("Tab providers", [
        "Decide where a tab appears: HPE hardware, physical hypervisor host, role permission",
        "Render the tab's HTML on the appliance (Handlebars templates)",
    ], TEAL),
    ("Script loader", [
        "Loads com-morpheus.js with Morpheus's CSP nonce",
        "The script acts only inside the COM tab, with a fixed list of actions",
    ], TEAL),
    ("Routes  /plugin/comMorpheus/…", [
        ("caps · action · job", "code"),
        "POST only; every request passes the security gates in D below",
    ], TEAL),
]
col2_right = [
    ("ComService", [
        "Matches hosts to COM servers",
        "Builds tab and cluster data, with caching",
        "Action rules: signed token, confirmed match only, 30 s cooldown per server",
    ], TEAL),
    ("MAC index + matcher", [
        "COM server list + every server's adapter MACs",
        "Built in the background; refreshed every 6 hours",
    ], TEAL),
    ("ComClient", [
        "OAuth2 client-credentials token",
        "HTTPS to the COM API, optional proxy",
        "Reads cached from 1 minute to 24 hours",
    ], TEAL),
]
col3 = [
    ("GreenLake sign-in (SSO)", [
        "Issues a token for the API client (client ID + secret)",
        "One shared identity for the whole appliance",
    ], GREEN),
    ("COM API: reads", [
        ("/servers · /servers/{id}", "code"),
        ("/servers/{id}/inventory", "code"),
        ("/servers/{id}/security-parameters", "code"),
        ("/alerts · warranty · firmware bundles · groups", "code"),
    ], GREEN),
    ("COM API: jobs", [
        ("POST /jobs   ·   GET /jobs/{id}", "code"),
        "Six fixed job templates: UID on, UID off, Collect inventory, iLO reset, COM maintenance on, COM maintenance off",
    ], GREEN),
]

# measure heights so the three zones line up
def measure(cards, w):
    return sum(card(0, 0, w, a, b, c, draw=False) + 12 for a, b, c in cards)

inner = 2 * 14
h1 = measure(col1, COL1_W - inner)
h2 = max(measure(col2_left, (COL2_W - 3 * 14) // 2), measure(col2_right, (COL2_W - 3 * 14) // 2))
h3 = measure(col3, COL3_W - inner)
DATA_H = 112
ZONE_H = 66 + max(h1 + 54, h2 + DATA_H + 18, h3 + 54)

zone(COL1_X, TOP, COL1_W, ZONE_H, "① Your browser", "Morpheus web UI: nothing to install", BLUE)
zone(COL2_X, TOP, COL2_W, ZONE_H, "② Morpheus appliance", "com-morpheus-plugin jar, running inside Morpheus", TEAL)
zone(COL3_X, TOP, COL3_W, ZONE_H, "③ HPE GreenLake (cloud)", "Compute Ops Management API", GREEN)

end1 = stack(COL1_X + 14, TOP + 66, COL1_W - inner, col1)
half = (COL2_W - 3 * 14) // 2
stack(COL2_X + 14, TOP + 66, half, col2_left)
stack(COL2_X + 28 + half, TOP + 66, half, col2_right)
end3 = stack(COL3_X + 14, TOP + 66, COL3_W - inner, col3)

# Morpheus data strip at the bottom of the appliance zone
dy = TOP + ZONE_H - DATA_H - 14
rect(COL2_X + 14, dy, COL2_W - 28, DATA_H, "#ffffff", GRAY[1], rx=8, sw=1.2, dash="5 4")
t(COL2_X + 28, dy + 24, "Morpheus data the plugin reads (it never writes)", 15, "bold", GRAY[1])
para(COL2_X + 28, dy + 34, COL2_W - 56,
     "Hosts and their hardware vendor · network-interface MAC addresses · tags com-serial / com-id · "
     "cluster membership · the user's tenant, roles and permissions · plugin settings "
     "(the API client secret stays here and never reaches a browser)", 13)

para(COL1_X + 14, TOP + ZONE_H - 46, COL1_W - 28,
     "The browser never calls GreenLake. Open iLO is a plain link to the iLO's address.", 12, MUTED)
para(COL3_X + 14, TOP + ZONE_H - 46, COL3_W - 28,
     "Outbound HTTPS (443) from the appliance only. Nothing in GreenLake calls Morpheus.", 12, MUTED)

# arrows between zones
ay = TOP + 140
arrow(COL1_X + COL1_W + 4, ay, COL2_X - 4, ay, BLUE[1], "tab\nHTML", ly=ay - 26, both=True)
arrow(COL1_X + COL1_W + 4, ay + 290, COL2_X - 4, ay + 290, AMBER[1], "action\nPOST", ly=ay + 264)
arrow(COL2_X + COL2_W + 4, ay + 100, COL3_X - 4, ay + 100, GREEN[1], "HTTPS\n443", ly=ay + 74, both=True)

# ---------------------------------------------------------------- the four flows
FY = TOP + ZONE_H + 44
t(W / 2, FY, "What happens, step by step", 22, "bold", INK, "middle")
FY += 22
GAP = 16
PW = (W - 2 * 24 - 3 * GAP) / 4
flows = [
    ("A.  Matching a host to a COM server", TEAL, [
        ("Morpheus already stores each host's network-interface MAC addresses.", "1"),
        ("COM's inventory lists every server's network-adapter MACs.", "2"),
        ("The plugin compares globally unique MACs only. It skips locally administered (virtual) ones.", "3"),
        ("Result: Confirmed (all MACs on one COM server), Probable, Conflict or No match.", "4"),
        ("A com-serial= or com-id= tag can choose the server shown. For actions, the tag must agree with the host's own MACs.", "5"),
        ("No agent, no SSH, no iLO login: only data Morpheus and COM already have.", "note"),
    ]),
    ("B.  Opening a host's tab", BLUE, [
        ("Morpheus asks the tab provider whether to show the tab: HPE hardware, physical hypervisor host, role permission.", "1"),
        ("ComService looks up the host's match. On first use the index builds in the background and the tab says so.", "2"),
        ("ComClient reads the server, alerts, iLO security, warranty, firmware baseline, group and inventory.", "3"),
        ("Reads are cached: server and alerts 1 min, security 5 min, groups 1 h, warranty 6 h, bundles 24 h.", "4"),
        ("The appliance renders the HTML and adds a signed action token for that host.", "5"),
    ]),
    ("C.  Cluster summary  (0.4.0)", BLUE, [
        ("Morpheus lists the cluster's hypervisor hosts.", "1"),
        ("Three bulk COM reads cover the whole cluster: server list, active alerts, warranties. There's no call per host.", "2"),
        ("One row per host: match, model, serial, health, power, alerts, support, COM maintenance, links to the host and to COM.", "3"),
        ("Totals: hosts in COM, health, hosts with alerts, support issues.", "4"),
        ("Read-only. No buttons on this tab.", "note"),
    ]),
    ("D.  Running an action", AMBER, [
        ("Every gate must pass, in this order:", "plain"),
        ("Enable actions is on (administrator setting)", "✓"),
        ("POST with X-Requested-With, same-origin, Morpheus CSRF", "✓"),
        ("Role has Run HPE COM actions (full)", "✓"),
        ("Signed action token for this host", "✓"),
        ("Host is in the user's tenant and is a hypervisor host", "✓"),
        ("Confirmed MAC match (a tag must agree)", "✓"),
        ("No action on this server in the last 30 s", "✓"),
        ("ComService creates the COM job and logs the Morpheus user who asked.", "→"),
        ("The browser polls the job's progress (this server's jobs only).", "→"),
    ]),
]


def flow_items(items):
    return [(s, b if b in ("plain", "note") else (f"{b}." if b.isdigit() else b)) for s, b in items]


fh = max(card(0, 0, PW, title, flow_items(items), col, size=13, title_size=15, draw=False)
         for title, col, items in flows)
for i, (title, col, items) in enumerate(flows):
    x = 24 + i * (PW + GAP)
    rect(x, FY, PW, fh, col[0], col[1], rx=12, sw=2)
    card(x, FY, PW, title, flow_items(items), col, size=13, title_size=15, draw=False)
    # draw the card body without its white box, on the colored panel
    saved = len(out)
    card(x, FY, PW, title, flow_items(items), col, size=13, title_size=15)
    del out[saved]              # drop the white rect, keep the text

# ---------------------------------------------------------------- identity + footer
IY = FY + fh + 24
ih = card(24, IY, W - 48, "Who acts in COM?", [
    ("Every read and every job uses one GreenLake API client that the administrator configures in the plugin settings. "
     "COM sees that client, not the Morpheus user. Morpheus roles decide who can see the tabs (View HPE COM data) and "
     "who can press the buttons (Run HPE COM actions). Each job is logged in Morpheus with the user who started it.", "plain"),
], AMBER, size=14, draw=False)
rect(24, IY, W - 48, ih, AMBER[0], AMBER[1], rx=12, sw=2)
saved = len(out)
card(24, IY, W - 48, "Who acts in COM?", [
    ("Every read and every job uses one GreenLake API client that the administrator configures in the plugin settings. "
     "COM sees that client, not the Morpheus user. Morpheus roles decide who can see the tabs (View HPE COM data) and "
     "who can press the buttons (Run HPE COM actions). Each job is logged in Morpheus with the user who started it.", "plain"),
], AMBER, size=14)
del out[saved]

FOOT = IY + ih + 30
t(24, FOOT, "Unofficial community project: not an HPE or Morpheus Data product, and not endorsed or supported by HPE. "
  "HPE, GreenLake, Compute Ops Management, Morpheus and iLO are trademarks of Hewlett Packard Enterprise. See NOTICE.md.",
  12, "normal", MUTED)
H = FOOT + 22

markers = "\n".join(f'<marker id="a{c.lstrip("#")}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>' for c in sorted(MARKERS))
head = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">
<title>How com-morpheus-plugin works</title>
<desc>Architecture and request flows of com-morpheus-plugin: the browser, the plugin inside the Morpheus appliance, and the HPE Compute Ops Management API in GreenLake.</desc>
<defs>
{markers}
</defs>
<rect width="{W}" height="{H}" fill="#ffffff"/>
<text x="24" y="48" font-size="30" font-weight="bold" fill="{INK}">How com-morpheus-plugin works</text>
<text x="24" y="76" font-size="15" fill="{MUTED}">Shows HPE Compute Ops Management (COM) data for physical HPE servers inside Morpheus, and runs a few safe COM jobs.</text>
'''
out_path = Path(__file__).resolve().parent.parent / "docs/images/how-it-works.svg"
out_path.write_text(head + "\n".join(out) + "\n</svg>\n")
print(out_path, W, H)
