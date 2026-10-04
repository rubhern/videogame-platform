"""Runtime boundary regressions without model downloads in CI."""
from types import SimpleNamespace
import unittest
from helper import Translator, MAX_DECODE, MAX_SOURCE


class Tokens:
    def encode(self, text, out_type=str):
        return text.split()

    def decode(self, tokens):
        return " ".join(tokens)


class Engine:
    def translate_batch(self, chunks, **_settings):
        return [SimpleNamespace(hypotheses=[[x for x in chunk if x != "</s>"]]) for chunk in chunks]


class RuntimeTests(unittest.TestCase):
    def translator(self):
        translator = Translator.__new__(Translator)
        translator.source = Tokens()
        translator.target = Tokens()
        translator.engine = Engine()
        translator.normalize = lambda text: text
        translator.add_eos = True
        translator.revision = "fixture-v1"
        return translator

    def test_long_input_is_complete_and_not_silently_truncated(self):
        source = " ".join(["explore"] * 1200)
        result = self.translator().translate(source)
        self.assertEqual(result["text"], source)

    def test_both_conversion_formats_handle_source_eos(self):
        for needs_eos in (True, False):
            translator = self.translator()
            translator.add_eos = needs_eos
            self.assertEqual(translator.translate("A warrior explores.")["text"], "A warrior explores.")

    def test_blank_invalid_oversized_source_and_incomplete_output_are_rejected(self):
        translator = self.translator()
        for source in ("", " ", "x\0x", "x" * (MAX_SOURCE + 1), None):
            with self.assertRaises(ValueError):
                translator.translate(source)
        translator.engine.translate_batch = lambda *_args, **_kwargs: [SimpleNamespace(hypotheses=[["x"] * MAX_DECODE])]
        with self.assertRaises(ValueError):
            translator.translate("Source.")


if __name__ == "__main__":
    unittest.main()
