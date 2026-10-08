"""Salin APK entri demi entri (metode kompresi dipertahankan), ganti/tambah file, buang tanda tangan v1 lama.

python3 repack.py in.apk out.apk path/dalam/apk=/file/lokal ...
"""
import sys
import zipfile

src, dst = sys.argv[1], sys.argv[2]
repl = dict(a.split('=', 1) for a in sys.argv[3:])
SIG = ('.SF', '.RSA', '.DSA', '.EC')


def is_old_sig(name):
    return name.startswith('META-INF/') and name.count('/') == 1 and (name.endswith(SIG) or name == 'META-INF/MANIFEST.MF')


with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
    done = set()
    for info in zin.infolist():
        if is_old_sig(info.filename):
            continue
        data = open(repl[info.filename], 'rb').read() if info.filename in repl else zin.read(info.filename)
        done.add(info.filename)
        ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        ni.compress_type = info.compress_type
        ni.external_attr = info.external_attr
        zout.writestr(ni, data, compress_type=info.compress_type, compresslevel=9 if info.compress_type == zipfile.ZIP_DEFLATED else None)
    for name, path in repl.items():
        if name in done:
            continue
        ni = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 1, 1, 2))
        ni.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(ni, open(path, 'rb').read(), compresslevel=9)
        print('tambah:', name)
print('ganti:', ', '.join(sorted(n for n in repl if n in done)))
