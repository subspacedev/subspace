// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service;

import space.getsub.service.PingBatchResultParcel;

/**
 * Answers one IPingService.pingBatch call, from :ping back to :bg.
 *
 * One callback object per call, so no request id is needed. `oneway` so a slow
 * :bg can never stall the :ping worker that is next in line.
 *
 * The result carries keys, delays and LatencyOutcome ordinals only — never
 * libXray's per-item `error` text, which can quote the config (ARCHITECTURE.md §5.6).
 */
oneway interface IPingCallback {
    void onResult(in PingBatchResultParcel result);
}
