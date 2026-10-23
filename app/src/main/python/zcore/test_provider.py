"""zcore/provider smoke tests."""
import os
import sys
import unittest

_F = chr(95) + chr(95) + "file" + chr(95) + chr(95)
_HERE = os.path.dirname(os.path.abspath(globals()[_F]))
sys.path.insert(0, os.path.dirname(_HERE))

from zcore import provider as pv


class DetectTest(unittest.TestCase):
    def test_chat_default(self):
        self.assertEqual(
            pv.detect_provider("https://api.deepseek.com/chat/completions"),
            pv.PROVIDER_CHAT,
        )
        self.assertEqual(pv.detect_provider(""), pv.PROVIDER_CHAT)
        self.assertEqual(pv.detect_provider(None), pv.PROVIDER_CHAT)

    def test_anthropic(self):
        self.assertEqual(
            pv.detect_provider("https://api.anthropic.com/v1/messages"),
            pv.PROVIDER_ANTHROPIC,
        )

    def test_responses(self):
        self.assertEqual(
            pv.detect_provider("https://api.openai.com/v1/responses"),
            pv.PROVIDER_RESPONSES,
        )


class MergeSystemTest(unittest.TestCase):
    def test_single_system_untouched(self):
        msgs = [
            {"role": "system", "content": "a"},
            {"role": "user", "content": "q"},
        ]
        out = pv.merge_system_chat(msgs)
        self.assertEqual(len(out), 2)
        self.assertEqual(out[0]["content"], "a")

    def test_multiple_system_merged(self):
        msgs = [
            {"role": "system", "content": "a"},
            {"role": "user", "content": "q1"},
            {"role": "assistant", "content": "r1"},
            {"role": "system", "content": "b"},
            {"role": "user", "content": "q2"},
        ]
        out = pv.merge_system_chat(msgs)
        self.assertEqual(len(out), 4)
        self.assertEqual(out[0]["role"], "system")
        self.assertEqual(out[0]["content"], "a" + chr(10) + chr(10) + "b")
        self.assertEqual(out[1]["content"], "q1")
        self.assertEqual(out[-1]["content"], "q2")

    def test_no_system(self):
        out = pv.merge_system_chat([{"role": "user", "content": "q"}])
        self.assertEqual(len(out), 1)

    def test_empty(self):
        self.assertEqual(pv.merge_system_chat([]), [])
        self.assertEqual(pv.merge_system_chat(None), None)


class AnthropicTest(unittest.TestCase):
    def test_request(self):
        url, headers, body = pv.to_anthropic_request(
            "https://api.anthropic.com/chat/completions",
            [
                {"role": "system", "content": "sys"},
                {"role": "user", "content": "hi"},
            ],
            "claude-3", 0.5, 1000,
        )
        self.assertTrue(url.endswith("/v1/messages"))
        self.assertIn("x-api-key", headers)
        self.assertIn("anthropic-version", headers)
        self.assertEqual(body["system"], "sys")
        self.assertEqual(len(body["messages"]), 1)
        self.assertEqual(body["messages"][0]["role"], "user")
        self.assertEqual(body["max_tokens"], 1000)

    def test_response(self):
        obj = {"content": [
            {"type": "thinking", "thinking": "hmm"},
            {"type": "text", "text": "hello"},
        ]}
        c, r = pv.from_anthropic(obj)
        self.assertEqual(c, "hello")
        self.assertEqual(r, "hmm")


class ResponsesTest(unittest.TestCase):
    def test_request(self):
        url, headers, body = pv.to_responses_request(
            "https://api.openai.com/chat/completions",
            [
                {"role": "system", "content": "sys"},
                {"role": "user", "content": "hi"},
            ],
            "gpt-4o", 0.3,
        )
        self.assertTrue(url.endswith("/v1/responses"))
        self.assertEqual(body["instructions"], "sys")
        self.assertEqual(len(body["input"]), 1)

    def test_response_output_text(self):
        c, r = pv.from_responses({"output_text": "hi"})
        self.assertEqual(c, "hi")

    def test_response_items(self):
        obj = {"output": [
            {"type": "reasoning", "summary": [
                {"type": "summary_text", "text": "think"}
            ]},
            {"type": "message", "content": [
                {"type": "output_text", "text": "hi"}
            ]},
        ]}
        c, r = pv.from_responses(obj)
        self.assertEqual(c, "hi")
        self.assertEqual(r, "think")


class ChatTest(unittest.TestCase):
    def test_response(self):
        obj = {"choices": [{"message": {
            "content": "hi", "reasoning_content": "r"
        }}]}
        c, r = pv.from_chat(obj)
        self.assertEqual(c, "hi")
        self.assertEqual(r, "r")


if True:
    unittest.main()
