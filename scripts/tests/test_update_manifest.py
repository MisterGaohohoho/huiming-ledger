import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('update_manifest', Path(__file__).parents[1]/'publish-update-manifest.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class UpdateManifestTest(unittest.TestCase):
    def metadata(self):
        return dict(version='0.5.119-youshu.1', versionCode=125, package='net.ankio.ledger',
                    abi='arm64-v8a', signed=True, sha256='a'*64)

    def test_channel_has_relative_versioned_paths_and_finite_validity(self):
        value=MODULE.payload(self.metadata(), 21000000, '更新说明', 1800000000)
        self.assertEqual(value['apkPath'], '/releases/v0.5.119-youshu.1/app.apk')
        self.assertEqual(value['sourcePath'], '/releases/v0.5.119-youshu.1/source.zip')
        self.assertEqual(value['expiresAt']-value['issuedAt'], 30*86400)
        self.assertNotIn('apkUrl', value)
        for metadata in [dict(self.metadata(), signed=False), dict(self.metadata(), package='other'),
                         dict(self.metadata(), version='../bad'), dict(self.metadata(), abi='x86_64')]:
            with self.assertRaises(AssertionError): MODULE.payload(metadata, 100, '', 1800000000)

    def test_real_signature_round_trip_tampering_and_temporary_key_removal(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);key=root/'fixture.pem';pub=root/'fixture.der'
            subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(key)],
                           check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            subprocess.run(['openssl','pkey','-in',str(key),'-pubout','-outform','DER','-out',str(pub)],
                           check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            with patch.object(MODULE,'PUBLIC_KEY',pub), patch.dict(os.environ,{'UPDATE_MANIFEST_PRIVATE_KEY':key.read_text()}):
                value=MODULE.payload(self.metadata(),100,'测试',1800000000)
                envelope=MODULE.sign(value,root)
                self.assertEqual(MODULE.verify(envelope,root),value)
                self.assertFalse((root/'private.pem').exists())
                bad={**envelope,'payload':MODULE.base64.b64encode(b'changed').decode()}
                with self.assertRaises(RuntimeError): MODULE.verify(bad,root)


if __name__=='__main__': unittest.main()
