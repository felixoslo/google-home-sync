#!/usr/bin/env python3
"""Root-run read-only Homey export, publish a stripped cached version for a token-protected LAN HTTPS endpoint."""
import datetime, json, os, pathlib, sys, tempfile, grp
sys.path.insert(0,'/srv/linuxserver/homey-google-room-sync/server')
from homey_rooms import export_homey
OUT=pathlib.Path('/var/lib/hsc-dashboard/homey-room-sync.json')
obj=export_homey()
if len(obj['devices'])<5:
    raise ValueError('Homey inventory unexpectedly small; refusing to overwrite cache')
devs=[{'name':d['name'],'room':d.get('room'),'deviceClass':d.get('deviceClass')} for d in obj['devices']]
payload={'schemaVersion':1,'source':'Homey Pro','generatedAt':obj['generatedAt'],
         'devices':devs,'summary':{'totalDevices':len(devs),'totalZones':len(obj['zones'])}}
fd,tmp=tempfile.mkstemp(prefix='.homey-room-',dir=str(OUT.parent))
try:
    os.fchmod(fd,0o640)
    os.fchown(fd,0,grp.getgrnam('hscdash').gr_gid)
    with os.fdopen(fd,'w',encoding='utf-8') as stream:
        json.dump(payload,stream,ensure_ascii=False,separators=(',',':'))
        stream.write('\n')
    os.replace(tmp,OUT)
finally:
    if os.path.exists(tmp):os.unlink(tmp)
print('Homey room sync cache updated, devices:',len(devs))
