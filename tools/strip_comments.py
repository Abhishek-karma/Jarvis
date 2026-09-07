#!/usr/bin/env python3
"""One-line doc policy enforcement: remove all comments except single-line /** */ KDoc."""
import sys, pathlib

def strip(src: str) -> str:
    out = []          # list of (char) or markers
    i, n = 0, len(src)
    NORMAL, LINE, BLOCK, STR, TSTR, CHAR = range(6)
    st = NORMAL
    block_start = None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if st == NORMAL:
            if c == '/' and nxt == '/':
                st = LINE; i += 2; continue
            if c == '/' and nxt == '*':
                st = BLOCK; block_start = i; i += 2; continue
            if c == '"':
                if src[i:i + 3] == '"""':
                    st = TSTR; out.append('"""'); i += 3; continue
                st = STR; out.append(c); i += 1; continue
            if c == "'":
                st = CHAR; out.append(c); i += 1; continue
            out.append(c); i += 1
        elif st == LINE:
            if c == '\n':
                st = NORMAL; out.append('\n')
            i += 1
        elif st == BLOCK:
            if c == '*' and nxt == '/':
                end = i + 2
                seg = src[block_start:end]
                # keep only single-line KDoc /** ... */
                body = seg[2:-2].strip()
                if seg.startswith('/**') and '\n' not in body:
                    out.append(seg)
                st = NORMAL
                i = end
            else:
                i += 1
        elif st == STR:
            if c == '\\':
                out.append(src[i:i + 2]); i += 2; continue
            if c == '"':
                st = NORMAL
            out.append(c); i += 1
        elif st == TSTR:
            if src[i:i + 3] == '"""':
                st = NORMAL; out.append('"""'); i += 3; continue
            out.append(c); i += 1
        elif st == CHAR:
            if c == '\\':
                out.append(src[i:i + 2]); i += 2; continue
            if c == "'":
                st = NORMAL
            out.append(c); i += 1
    return ''.join(out)

def main():
    roots = sys.argv[1:] or ['.']
    changed = 0
    for root in roots:
        p = pathlib.Path(root)
        if not p.exists():
            continue
        pattern = '**/*.kt' if p.is_dir() else ''
        files = p.rglob('*.kt') if p.is_dir() else [p]
        for f in files:
            s = f.read_text(encoding='utf-8')
            r = strip(s)
            # collapse trailing spaces left on now-blank lines
            r = '\n'.join(line.rstrip() for line in r.split('\n'))
            if r != s:
                f.write_text(r, encoding='utf-8')
                changed += 1
    print(f'cleaned {changed} files')

if __name__ == '__main__':
    main()
