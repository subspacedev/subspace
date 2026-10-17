// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service;

import space.getsub.service.IPingCallback;
import space.getsub.service.PingBatchParcel;

/**
 * The channel from :bg to :ping (libXray stock upgrade spec §4).
 *
 * libXray's `pingBatch` refuses to run in a process holding a managed Xray
 * instance, and the tunnel's instance lives in :bg, so proxy-head latency runs
 * in its own process with its own Go runtime.
 *
 * `oneway`: a batch blocks for up to its timeout, and :bg must not park a binder
 * thread on it. Batches run one at a time in :ping (`pingBatch` holds libXray's
 * `coreServerMu` for the whole call); the answer arrives on `callback`.
 *
 * At most 5 configs per request (`PingBatch.MAX_CONFIGS`, libXray's
 * `maxPingBatchConfigs`). Five generated configs are a few tens of KB, well under
 * binder's 1 MB transaction ceiling.
 */
interface IPingService {
    oneway void pingBatch(in PingBatchParcel request, IPingCallback callback);
}
