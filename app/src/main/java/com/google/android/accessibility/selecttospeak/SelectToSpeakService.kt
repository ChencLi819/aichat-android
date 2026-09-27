package com.google.android.accessibility.selecttospeak

import com.jev.probe.capture.ChatCaptureService

/**
 * The live capture service, registered under this system-style class name so
 * WeChat exposes its node tree. All logic lives in [ChatCaptureService]; only
 * the class name differs.
 *
 * Verified in P1 on WeChat 8.0.78: a plainly-named service reads back a single
 * empty node, this class reads the full chat. (The name was briefly changed to
 * TalkBack's while WeChat was suspected of having blocklisted it — that was
 * wrong: WeChat had put ITSELF into a protected state, and reinstalling WeChat
 * restored reading with this name untouched. Do not rename without re-testing.)
 */
class SelectToSpeakService : ChatCaptureService()
