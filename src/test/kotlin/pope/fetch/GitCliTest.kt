package pope.fetch

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitCliTest {
    @Test
    fun `run returns stdout on success`() {
        val output = GitCli.run(null, "--version")

        assertTrue(output.contains("git version"))
    }

    @Test
    fun `run throws with git's stderr on a failing command`() {
        val exception = assertFailsWith<GitCommandFailedException> { GitCli.run(null, "not-a-real-git-subcommand") }

        assertTrue(exception.message!!.contains("git"))
    }
}
