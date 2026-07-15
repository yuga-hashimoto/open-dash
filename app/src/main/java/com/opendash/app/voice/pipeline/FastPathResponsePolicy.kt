package com.opendash.app.voice.pipeline

import com.opendash.app.tool.ToolResult
import com.opendash.app.voice.fastpath.FastPathMatch

/**
 * A matcher-provided confirmation is safe only when the tool did not report
 * a failure. A null result is valid for speak-only fast paths.
 */
internal fun canUseFastPathSpokenConfirmation(
    match: FastPathMatch,
    result: ToolResult?
): Boolean = match.spokenConfirmation != null &&
    (result == null || (result.success && result.confirmed))
