import importlib.util
import pathlib
import tempfile
import unittest

MODULE = pathlib.Path('/srv/linuxserver/homey-google-room-sync/server/homey_rooms.py')
spec = importlib.util.spec_from_file_location('homey_rooms', MODULE)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class RoomTests(unittest.TestCase):
    def setUp(self):
        self.homey = {'schemaVersion': 1, 'devices': [
            {'id': 'h1', 'name': 'Andreas Spot', 'room': 'Andreas'},
            {'id': 'h2', 'name': 'Stue Spot', 'room': 'Stue'},
            {'id': 'h3', 'name': 'Stue Spot', 'room': 'Kjøkken'},
            {'id': 'h4', 'name': 'Status', 'room': None},
        ]}
        self.google = {'schemaVersion': 1, 'structure': {'id': 'home1', 'name': 'Hjem'},
                       'rooms': [{'id': 'groom1','name':'Andreas'}],
                       'devices': [{'id': 'g1','name':'Andreas Spot','roomId': None},
                                   {'id': 'g2','name':'Stue Spot','roomId': None},
                                   {'id': 'g3','name':'Status','roomId': None},
                                   {'id': 'g4','name':'Unknown','roomId': None}]}
    def test_plan_only_unambiguous(self):
        plan = mod.make_plan(self.homey,self.google)
        self.assertEqual(plan['summary'], {'googleDevices': 4, 'moves': 1,'newRooms':0,'skipped':3})
        self.assertEqual(plan['proposedMoves'][0]['googleId'],'g1')
        self.assertEqual(plan['status'],'DRY_RUN_ONLY')
    def test_idempotent_when_in_right_room(self):
        self.google['devices'][0]['roomId'] = 'groom1'
        self.assertEqual(mod.make_plan(self.homey,self.google)['summary']['moves'],0)
    def test_missing_room_requires_creation(self):
        self.homey['devices'][0]['room']='Loft'
        plan=mod.make_plan(self.homey,self.google)
        self.assertEqual(plan['proposedNewRooms'], ['Loft'])
    def test_reject_ambiguous_google_rooms(self):
        self.google['rooms'] += [{'id':'another','name':'Andreas'}]
        plan=mod.make_plan(self.homey,self.google)
        self.assertEqual(plan['summary']['moves'],0)
        self.assertIn('ambiguous_google_room', [x['reason'] for x in plan['skipped']])
    def test_invalid_missing_structure(self):
        self.google['structure']={}
        with self.assertRaises(ValueError):mod.make_plan(self.homey,self.google)
    def test_name_normalization(self):
        self.assertEqual(mod.normalized(' Stue—Spot  '),mod.normalized('STUE SPOT'))
        self.assertNotEqual(mod.normalized('Bad'),mod.normalized('Bad Lille'))

if __name__=='__main__': unittest.main()
