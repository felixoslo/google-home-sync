import importlib.util,json,pathlib,tempfile,unittest
from io import BytesIO
SPEC=importlib.util.spec_from_file_location('room_sync_test',pathlib.Path('/srv/linuxserver/homey-google-room-sync/server/room_sync_diagnostics.py'))
mod=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)

class Handler:
    def __init__(self,key,obj):
        raw=json.dumps(obj).encode()
        self.headers={'X-Room-Sync-Key':key,'Content-Length':str(len(raw))}
        self.rfile=BytesIO(raw)
        self.close_connection=False
        self.result=None
    def json(self,response,status):
        self.result=status

class ReceiverTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        base=pathlib.Path(self.temp.name)
        mod.TOKEN=base/'key'
        mod.TOKEN.write_text('fixture-only')
        mod.DEST=base/'recent.json'
        mod.FAILURES=base/'errors.json'
        mod.MOVE_TESTS=base/'move-tests.json'
        self.report={
          'schemaVersion':1,'appVersion':'test',
          'operation':'roomMoveTest','timestamp':'2026-11-18T20:30:00Z',
          'errorCode':0,'errorMessage':'','subErrors':[],
          'result':{'mode':'SAME_ROOM_PERMISSION_PROBE','deviceName':'Lamp',
          'fromRoom':'Stue','toRoom':'Stue','outcome':'API_ACCEPTED_NOOP',
          'details':'same-room-check','verified':True,'attempted':True}}
    def tearDown(self):
        self.temp.cleanup()
    def test_store_and_retain_100_private_records(self):
        for _ in range(103):
            h=Handler('fixture-only',self.report)
            mod.receive(h)
            self.assertEqual(h.result,200)
        self.assertEqual(len(json.loads(mod.MOVE_TESTS.read_text())),100)
        self.assertEqual(len(json.loads(mod.DEST.read_text())),20)
        self.assertEqual(mod.MOVE_TESTS.stat().st_mode & 0o777,0o600)
    def test_unauthorized_denied(self):
        h=Handler('incorrect',self.report)
        mod.receive(h)
        self.assertEqual(h.result,403)
        self.assertFalse(mod.MOVE_TESTS.exists())
    def test_unknown_result_field_denied(self):
        report=dict(self.report)
        report['result']=dict(self.report['result'],unexpected='not allowed')
        h=Handler('fixture-only',report)
        mod.receive(h)
        self.assertEqual(h.result,400)
        self.assertFalse(mod.MOVE_TESTS.exists())
if __name__=='__main__':unittest.main()
