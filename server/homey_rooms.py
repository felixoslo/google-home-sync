#!/usr/bin/env python3
"""Read-only Homey inventory and conservative Google Home room migration planner.

No Homey write endpoints or Google credentials are used.  Output contains no API key.
"""
import argparse
import collections
import datetime
import json
import os
import pathlib
import re
import stat
import tempfile
import unicodedata
import urllib.request

HOMEY_URL = os.environ.get('HOMEY_URL', 'https://YOUR_HOMEY_HOST')
KEY_FILE = pathlib.Path(os.environ.get('HOMEY_API_KEY_FILE', '/path/to/private/key'))
PROJECT = pathlib.Path(os.environ.get('ROOM_SYNC_PROJECT_DIR', '/opt/homey-room-sync'))
IGNORED_ZONES = {'hjem', 'første etasje', 'andre etasje', 'system', 'stue system'}


def normalized(value):
    value = unicodedata.normalize('NFKC', str(value or '')).casefold()
    return ' '.join(re.findall(r'[^\W_]+', value, flags=re.UNICODE))


def load_key():
    st = KEY_FILE.stat()
    if st.st_uid != 0 or stat.S_IMODE(st.st_mode) != 0o600:
        raise RuntimeError('Homey API key has unexpected permissions; refusing to read')
    value = KEY_FILE.read_text(encoding='utf-8').strip()
    if len(value) < 20 or any(ch.isspace() for ch in value):
        raise RuntimeError('Homey API key is incomplete')
    return value


def homey_get(path, key):
    req = urllib.request.Request(HOMEY_URL + path, headers={
        'Authorization': 'Bearer ' + key, 'Accept': 'application/json',
        'User-Agent': 'Homey-GoogleRoomSync-Readonly/1.0'}, method='GET')
    with urllib.request.urlopen(req, timeout=20) as resp:
        if resp.status != 200:
            raise RuntimeError('Homey returned HTTP ' + str(resp.status))
        data = resp.read(12 * 1024 * 1024 + 1)
    if len(data) > 12 * 1024 * 1024:
        raise RuntimeError('Oversized Homey response')
    parsed = json.loads(data)
    if not isinstance(parsed, dict):
        raise ValueError('Homey response is not a JSON object')
    return parsed.get('result', parsed)


def export_homey():
    key = load_key()
    zones = homey_get('/api/manager/zones/zone', key)
    devices = homey_get('/api/manager/devices/device', key)
    if not isinstance(zones, dict) or not isinstance(devices, dict):
        raise ValueError('Unexpected Homey zone/device API format')
    if not zones or not devices:
        raise ValueError('Homey returned empty inventory; refusing to overwrite export')

    def names(zone_id):
        path, seen = [], set()
        while zone_id and zone_id in zones and zone_id not in seen:
            seen.add(zone_id)
            zone = zones[zone_id]
            path.append(str(zone.get('name', '')).strip())
            zone_id = zone.get('parent')
        return list(reversed([p for p in path if p]))

    catalog = []
    for device_id, obj in devices.items():
        if not isinstance(obj, dict):
            continue
        path = names(obj.get('zone'))
        leaf = path[-1] if path else ''
        allowed = bool(leaf and normalized(leaf) not in IGNORED_ZONES)
        catalog.append({
            'id': str(device_id), 'name': str(obj.get('name', '')).strip(),
            'room': leaf if allowed else None, 'zonePath': ' > '.join(path),
            'driverId': str(obj.get('driverId', '')), 'deviceClass': str(obj.get('class', '')),
            'available': bool(obj.get('available', False)),
        })
    return {
        'schemaVersion': 1, 'source': 'Homey Pro',
        'generatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'zones': [{'id': k, 'name': v.get('name'), 'parent': v.get('parent')}
                  for k, v in zones.items() if isinstance(v, dict)],
        'devices': sorted(catalog, key=lambda x: (x['zonePath'], x['name'])),
        'summary': {'totalDevices': len(catalog), 'totalZones': len(zones),
                    'roomAssigned': sum(x['room'] is not None for x in catalog),
                    'roomUnassigned': sum(x['room'] is None for x in catalog)},
    }


def indexed(items, key):
    result = collections.defaultdict(list)
    for item in items:
        if isinstance(item, dict):
            name = normalized(item.get(key))
            if name:
                result[name].append(item)
    return result


def make_plan(homey, google):
    if homey.get('schemaVersion') != 1:
        raise ValueError('Unrecognized Homey export schema')
    if google.get('schemaVersion') != 1:
        raise ValueError('Unrecognized Google inventory schema')
    structure = google.get('structure') or {}
    if not structure.get('id'):
        raise ValueError('A single explicit Google Home structure is required')
    rooms = google.get('rooms', [])
    gdevices = google.get('devices', [])
    if not isinstance(rooms, list) or not isinstance(gdevices, list):
        raise ValueError('Invalid Google Home inventory')
    hnames, rnames = indexed(homey.get('devices', []), 'name'), indexed(rooms, 'name')
    changes, skipped, create_rooms = [], [], set()
    for dev in gdevices:
        if not isinstance(dev, dict) or not dev.get('id'):
            continue
        matching = hnames.get(normalized(dev.get('name')), [])
        reason = None
        if len(matching) != 1:
            reason = 'no_exact_homey_name' if not matching else 'ambiguous_homey_name'
        elif not matching[0].get('room'):
            reason = 'homey_zone_not_a_room'
        if reason:
            skipped.append({'googleId': dev['id'], 'googleName': dev.get('name'), 'reason': reason})
            continue
        homey_dev = matching[0]
        target_room = homey_dev['room']
        possible = rnames.get(normalized(target_room), [])
        if len(possible) > 1:
            skipped.append({'googleId': dev['id'], 'googleName': dev.get('name'), 'reason': 'ambiguous_google_room'})
            continue
        target_id = possible[0].get('id') if possible else None
        if target_id and dev.get('roomId') == target_id:
            continue
        if not possible:
            create_rooms.add(target_room)
        changes.append({'googleId': str(dev['id']), 'googleName': str(dev.get('name', '')),
                        'homeyId': homey_dev['id'], 'targetRoom': target_room,
                        'targetRoomId': target_id, 'oldRoomId': dev.get('roomId'),
                        'match': 'exact_unique_name'})
    return {'schemaVersion': 1, 'status': 'DRY_RUN_ONLY', 'structure': structure,
            'proposedNewRooms': sorted(create_rooms),
            'proposedMoves': changes, 'skipped': skipped,
            'summary': {'googleDevices': len(gdevices), 'moves': len(changes),
                        'newRooms': len(create_rooms), 'skipped': len(skipped)},
            'warning': 'Requires explicit on-device confirmation. Never apply automatically.'}


def atomic_json(path, data):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix='.' + path.name, dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, sort_keys=True, indent=2)
            f.write('\n')
        os.replace(temp, path)
    finally:
        if os.path.exists(temp): os.unlink(temp)


def main():
    p = argparse.ArgumentParser(description='Homey / Google Home room sync (read-only)')
    sub = p.add_subparsers(dest='action', required=True)
    export = sub.add_parser('export'); export.add_argument('--out', default=str(PROJECT / 'output/homey-rooms.json'))
    plan = sub.add_parser('plan'); plan.add_argument('--homey', default=str(PROJECT / 'output/homey-rooms.json'))
    plan.add_argument('--google', required=True); plan.add_argument('--out', default=str(PROJECT / 'output/google-room-plan.json'))
    args = p.parse_args()
    if args.action == 'export':
        result = export_homey(); atomic_json(args.out, result)
        print(json.dumps({'file': args.out, 'summary': result['summary']}, ensure_ascii=False))
    else:
        result = make_plan(json.loads(pathlib.Path(args.homey).read_text()), json.loads(pathlib.Path(args.google).read_text()))
        atomic_json(args.out, result)
        print(json.dumps({'file': args.out, 'summary': result['summary']}, ensure_ascii=False))

if __name__ == '__main__':
    main()
