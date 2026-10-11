// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings.pages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftCommitTest {
    @Test
    fun aChangedValidDraftIsCommitted() {
        assertTrue(shouldCommitDraft(draft = "1.1.1.1", stored = "8.8.8.8", isValid = true))
    }

    @Test
    fun anUnchangedDraftIsNotCommitted() {
        assertFalse(shouldCommitDraft(draft = "8.8.8.8", stored = "8.8.8.8", isValid = true))
    }

    @Test
    fun aChangedInvalidDraftIsNotCommitted() {
        assertFalse(shouldCommitDraft(draft = "not an ip", stored = "8.8.8.8", isValid = false))
    }

    // Blank is how the user clears a field; each caller decides whether blank is valid.
    @Test
    fun aBlankDraftIsCommittedWhenTheCallerSaysItIsValid() {
        assertTrue(shouldCommitDraft(draft = "", stored = "8.8.8.8", isValid = true))
    }
}
