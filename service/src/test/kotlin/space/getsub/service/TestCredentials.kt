// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ProxyCredentials

/** Fixed inbound credentials for configs built in tests (M9-E spec §5.3: no test depends on an open inbound). */
internal val TEST_CREDENTIALS = ProxyCredentials("testuser", "testpass")
