package com.videogameplatform.identity.adapter.operator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.identity.application.AccountDeletionStore;
import com.videogameplatform.identity.application.AccountOperationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SyntheticLedgerCommandTest {
    @TempDir Path directory;
    final AccountDeletionStore.Work work =
            new AccountDeletionStore.Work("user", "subject", "request", "claim");

    @Test
    void zeroExitAndUnrelatedOrUntypedReceiptsNeverClaimCompletion() throws Exception {
        for (String receipt :
                new String[] {
                    "{}",
                    "{\"verified\":false}",
                    "{\"verified\":\"true\",\"requestId\":\"request\",\"userId\":\"user\"}",
                    "{\"verified\":true,\"requestId\":\"other\",\"userId\":\"user\"}"
                }) {
            var engine = engine(receipt);
            assertThatThrownBy(() -> engine.erase(work))
                    .isInstanceOf(AccountOperationException.class);
        }
        engine("{\"verified\":true,\"requestId\":\"request\",\"userId\":\"user\"}").erase(work);
    }

    SyntheticLedgerCommand engine(String receipt) throws Exception {
        Path executable = directory.resolve("private-command");
        Files.writeString(executable, "#!/bin/sh\ncat >/dev/null\nprintf '%s' '" + receipt + "'\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return new SyntheticLedgerCommand(
                executable.toString(),
                "https://identity.example.test/realms/test",
                JsonMapper.builder().build());
    }
}
