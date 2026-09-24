/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.tee

enum class TeeState {
    UNKNOWN,
    WORKING,
    BROKEN,
}

enum class TeeProbeResult {
    WORKING,
    TRANSIENT,
    BROKEN,
}
