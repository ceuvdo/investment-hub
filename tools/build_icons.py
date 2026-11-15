"""Bundle assets/img/ways/*.svg and assets/img/groups/*.svg into assets/js/icons.js (inline SVG, coloured by CSS).
Run after adding icons: python -I tools/build_icons.py"""
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '.')
out = {}
for kind in ('ways', 'groups'):
    d = root / 'assets' / 'img' / kind
    icons = {}
    for f in sorted(d.glob('*.svg')) if d.exists() else []:
        s = f.read_text(encoding='utf-8')
        s = re.sub(r'<\?xml[^>]*>|<!--.*?-->', '', s, flags=re.S)
        s = re.sub(r'\s+', ' ', s).strip()
        if not s.startswith('<svg') or re.search(r'<script|on\w+=|href=', s, re.I):
            print('skipped', f.name); continue
        icons[f.stem] = s
    out[kind] = icons
(root / 'assets' / 'js' / 'icons.js').write_text('window.ICONS=' + json.dumps(out, ensure_ascii=False) + ';\n', encoding='utf-8')
print({k: len(v) for k, v in out.items()})
