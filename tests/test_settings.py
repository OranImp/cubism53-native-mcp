import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from bridge.settings import state_directory


class StateDirectoryTest(unittest.TestCase):
    def test_source_defaults_to_private_per_checkout_state(self):
        with TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            local = root / 'LocalAppData'
            with patch.dict(os.environ, {'LOCALAPPDATA': str(local)}, clear=True):
                with patch('bridge.settings.ROOT', root / 'checkout-a'):
                    first = state_directory()
                with patch('bridge.settings.ROOT', root / 'checkout-b'):
                    second = state_directory()
            self.assertEqual(first.parent, local / 'Cubism53MCP' / 'source-state')
            self.assertEqual(second.parent, first.parent)
            self.assertNotEqual(first, second)

    def test_configured_state_is_used_without_localappdata(self):
        with TemporaryDirectory() as directory:
            explicit = Path(directory).resolve() / 'existing-state'
            with patch.dict(os.environ, {'CUBISM53_BRIDGE_STATE': str(explicit)}, clear=True):
                self.assertEqual(state_directory(), explicit)
