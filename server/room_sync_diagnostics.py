"""Bounded diagnostic-only receiver; no device actions or arbitrary paths."""
import pathlib,json,hmac,threading,time,os
LOCK=threading.Lock()
DEST=pathlib.Path('/var/lib/hsc-dashboard/room-sync-diagnostics/reports.json')
FAILURES=pathlib.Path('/var/lib/hsc-dashboard/room-sync-diagnostics/failures.json')
MOVE_TESTS=pathlib.Path('/var/lib/hsc-dashboard/room-sync-diagnostics/move-tests.json')
TOKEN=pathlib.Path('/etc/hsc-dashboard/room_sync_token')
def receive(handler):
    supplied=handler.headers.get('X-Room-Sync-Key','')
    expected=TOKEN.read_text().strip()
    if not supplied or not hmac.compare_digest(supplied,expected):
        handler.close_connection=True
        return handler.json({'error':'forbidden'},403)
    try:size=int(handler.headers.get('Content-Length','0'))
    except ValueError:return handler.json({'error':'invalid length'},400)
    if not 1 <= size <= 32768:
        handler.close_connection=True
        return handler.json({'error':'invalid size'},413)
    try:
        data=json.loads(handler.rfile.read(size))
        if not isinstance(data,dict) or data.get('schemaVersion')!=1:raise ValueError()
        allowed={'schemaVersion','appVersion','operation','timestamp','errorCode','errorMessage','subErrors','result'}
        if set(data)-allowed:raise ValueError()
        if data.get('operation') not in {'listAvailableAgents','initiateCloudAccountLink','v2ReadOnlyResults','v2TransportSelfTest','requestPermissions','googleSignIn','appFailure','roomMoveTest'}:raise ValueError()
        if data['operation'] in {'v2ReadOnlyResults','v2TransportSelfTest'}:
            result=data.get('result')
            if not isinstance(result,dict):raise ValueError()
            count_fields={'accessibleHomeCount','accountDeviceCount','unassignedDeviceCount',
              'selectedHomeDeviceCount','googleRoomCount','devicesWithoutRoomCount',
              'correctRoomCount','proposedMoveCount','proposedNewRoomCount',
              'skippedDeviceCount','missingHomeyLightSocketCount'}
            row_fields={
              'missingHomeyDevices':{'name','room','deviceClass'},
              'skippedGoogleDevices':{'name','reason'},
              'proposedRoomMoves':{'name','fromRoom','toRoom'},
              'googleDeviceRooms':{'id','name','room'},
            }
            allowed_result=count_fields | set(row_fields) | {'scope','selectedHome','accessibleHomeNames','unassignedDeviceNames',
              'providerFound','providerAlreadyLinked',
              'structuresError','providerError','roomPreviewError','permissionsState','synthetic','readOnly'}
            if set(result)-allowed_result:raise ValueError()
            for k,v in result.items():
                if k in {'accessibleHomeNames','unassignedDeviceNames'}:
                    if not isinstance(v,list) or len(v)>80 or not all(isinstance(x,str) and len(x)<=130 for x in v):raise ValueError()
                elif k in row_fields:
                    if not isinstance(v,list) or len(v)>80:raise ValueError()
                    for row in v:
                        if not isinstance(row,dict) or set(row)!=row_fields[k]:raise ValueError()
                        if any(not isinstance(x,str) or len(x)>100 for x in row.values()):raise ValueError()
                elif k in count_fields:
                    if type(v) is not int or not 0<=v<=10000:raise ValueError()
                elif k in {'providerFound','providerAlreadyLinked','synthetic','readOnly'}:
                    if v is not None and type(v) is not bool:raise ValueError()
                elif not isinstance(v,str) or len(v)>500:raise ValueError()
        elif data['operation']=='roomMoveTest':
            result=data.get('result')
            expected={'mode','deviceName','fromRoom','toRoom','outcome','details','verified','attempted'}
            if not isinstance(result,dict) or set(result)!=expected:raise ValueError()
            if type(result['verified']) is not bool or type(result['attempted']) is not bool:raise ValueError()
            if not all(isinstance(result[k],str) and len(result[k])<=200 for k in expected-{'verified','attempted'}):raise ValueError()
            if result['mode'] not in {'MOVE_UNASSIGNED','SAME_ROOM_PERMISSION_PROBE','NOT_ACCESSIBLE'}:raise ValueError()
            if result['outcome'] not in {'VERIFIED_MOVE','API_ACCEPTED_NOOP','NO_ACCESSIBLE_DEVICE','ERROR'}:raise ValueError()
        elif 'result' in data:raise ValueError()
        if not isinstance(data.get('subErrors'),list) or len(data['subErrors'])>30:raise ValueError()
        for e in data['subErrors']:
            if not isinstance(e,dict) or set(e)-{'code','reason','message','domain'}:raise ValueError()
            if any(len(str(v))>2000 for v in e.values()):raise ValueError()
        if len(str(data.get('errorMessage','')))>2000:raise ValueError()
    except (ValueError,TypeError,UnicodeError):return handler.json({'error':'invalid diagnostic'},400)
    with LOCK:
        try:items=json.loads(DEST.read_text())
        except (OSError,ValueError):items=[]
        if not isinstance(items,list):items=[]
        item={'received_utc_epoch':time.time(),'diagnostic':data}
        items.append(item)
        def atomic_json(path, records):
            temp=path.with_suffix('.new')
            with open(temp,'w') as f:
                os.fchmod(f.fileno(),0o600)
                json.dump(records,f,ensure_ascii=False)
            os.replace(temp,path)
        atomic_json(DEST,items[-20:])
        # Keep failures separately: frequent successful V2 scans must not push
        # an important ANR/crash out of the short 20-report history.
        if data.get('operation')=='appFailure':
            try: failures=json.loads(FAILURES.read_text())
            except (OSError,ValueError): failures=[]
            if not isinstance(failures,list): failures=[]
            failures.append(item)
            atomic_json(FAILURES,failures[-100:])
        if data.get('operation')=='roomMoveTest':
            try: tests=json.loads(MOVE_TESTS.read_text())
            except (OSError,ValueError): tests=[]
            if not isinstance(tests,list): tests=[]
            tests.append(item)
            atomic_json(MOVE_TESTS,tests[-100:])
    return handler.json({'received':True},200)
