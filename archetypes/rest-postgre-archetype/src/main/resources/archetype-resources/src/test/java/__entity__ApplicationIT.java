#set( $symbol_pound = '#' )
#set( $symbol_dollar = '$' )
#set( $symbol_escape = '\' )
package ${package};

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ${entity}ApplicationIT {

	@Test
	void contextLoads() {
		// This test verifies if the Spring application context loads successfully
		// by checking that all beans are created and wired properly
	}

}
