import copy
import importlib.util
from pathlib import Path
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('release_retention', Path(__file__).parents[1] / 'prune-releases.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseRetentionTest(unittest.TestCase):
    def setUp(self):
        self.tag = 'v0.5.120-youshu.2'
        apk = 'Youshu-Ledger-0.5.120-youshu.2-arm64-v8a.apk'
        self.latest = dict(id=3, tag_name=self.tag, draft=False, prerelease=False,
                           assets=[dict(name=name, size=100) for name in
                                   [apk, apk + '.sha256', 'build-info.json', 'Youshu-Ledger-source.zip', 'Youshu-Ledger-source.sha256']])
        self.channel = dict(versionName=self.tag[1:], packageName='net.ankio.ledger', abi='arm64-v8a', bytes=100,
                            apkPath=f'/releases/{self.tag}/app.apk', sourcePath=f'/releases/{self.tag}/source.zip')
        self.old = dict(id=2, tag_name='v0.5.120-youshu.1', draft=False, prerelease=False)
        self.preview = dict(id=1, tag_name='v0.5.119-rc.1', draft=False, prerelease=True)
        self.draft = dict(id=4, tag_name='v0.5.121-youshu.1', draft=True, prerelease=False)
        self.releases = [self.latest, self.old, self.preview, self.draft]

    def test_keep_stable_assets_and_unpublished_drafts(self):
        targets = MODULE.older_releases(self.releases, self.latest, self.channel, self.tag)
        self.assertEqual([item['id'] for item in targets], [2, 1])

    def test_refuse_cleanup_before_stable_channel_advances(self):
        channel = dict(self.channel, versionName='0.5.120-youshu.1')
        with self.assertRaises(RuntimeError):
            MODULE.older_releases(self.releases, self.latest, channel, self.tag)

    def test_refuse_incomplete_or_mismatched_assets(self):
        latest = copy.deepcopy(self.latest)
        latest['assets'].pop()
        with self.assertRaises(RuntimeError):
            MODULE.older_releases(self.releases, latest, self.channel, self.tag)
        with self.assertRaises(RuntimeError):
            MODULE.older_releases(self.releases, self.latest, dict(self.channel, bytes=101), self.tag)

    def test_refuse_prerelease_or_missing_keep_target(self):
        with self.assertRaises(RuntimeError):
            MODULE.older_releases(self.releases, dict(self.latest, prerelease=True), self.channel, self.tag)
        with self.assertRaises(RuntimeError):
            MODULE.older_releases([self.old], self.latest, self.channel, self.tag)

    def test_dry_run_never_deletes(self):
        with mock.patch.object(MODULE, 'gh', return_value=[self.releases]) as api, \
                mock.patch.object(MODULE, 'pointer', return_value=(self.latest, self.channel)):
            MODULE.prune('owner/repo', self.tag)
        api.assert_called_once()

    def test_delete_only_planned_release_objects_and_recheck_pointer(self):
        calls = []

        def api(*args):
            calls.append(args)
            if '--paginate' in args:
                return [self.releases]
            if args[-1] == 'DELETE':
                return None
            return self.old if args[1].endswith('/2') else self.preview

        with mock.patch.object(MODULE, 'gh', side_effect=api), \
                mock.patch.object(MODULE, 'pointer', return_value=(self.latest, self.channel)) as pointer:
            MODULE.prune('owner/repo', self.tag, apply=True)
        self.assertEqual([call[1] for call in calls if call[-1] == 'DELETE'],
                         ['repos/owner/repo/releases/2', 'repos/owner/repo/releases/1'])
        self.assertEqual(pointer.call_count, 3)
        self.assertFalse(any('/git/' in call[1] for call in calls))

    def test_stop_if_the_live_pointer_changes(self):
        with mock.patch.object(MODULE, 'gh', return_value=[self.releases]) as api, \
                mock.patch.object(MODULE, 'pointer', side_effect=[(self.latest, self.channel),
                    (dict(self.latest, tag_name='v0.5.121-youshu.1'), self.channel)]):
            with self.assertRaises(RuntimeError):
                MODULE.prune('owner/repo', self.tag, apply=True)
        self.assertFalse(any(call.args[-1] == 'DELETE' for call in api.call_args_list))


if __name__ == '__main__':
    unittest.main()
