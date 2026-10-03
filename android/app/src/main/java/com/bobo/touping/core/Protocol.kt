package com.bobo.touping.core

/**
 * 与 PC 端共用的协议常量。
 *
 * 改动这里必须同步修改 desktop/src/main/protocol.js，否则两端会对不上。
 */
object Protocol {
    const val DISCOVERY_PORT = 8767
    const val DEFAULT_CTRL_PORT = 8765
    const val DEFAULT_VIDEO_PORT = 8766
    const val DEFAULT_AUDIO_PORT = 8768

    /** 'M' << 8 | 'C' */
    const val MAGIC = 0x4D43
    const val VERSION = 1

    const val FLAG_KEYFRAME = 0x01
    const val FLAG_CONFIG = 0x02
    const val FLAG_LAST_PACKET = 0x04

    const val HEADER_SIZE = 20

    /** 单包负载上限，避免超过常见 1500 字节 MTU 触发 IP 分片。 */
    const val MAX_PAYLOAD = 1200
}
