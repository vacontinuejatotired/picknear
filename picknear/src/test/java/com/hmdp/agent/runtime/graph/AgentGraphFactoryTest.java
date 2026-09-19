package com.hmdp.agent.runtime.graph;

import com.hmdp.agent.access.AgentCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphFactoryTest {

    @Test
    void should_invoke_minimal_graph() throws Exception {
        AgentGraphFactory factory = new AgentGraphFactory();

        String output = factory.invoke(new AgentCommand("你好", "conv-1", 1010L));

        assertThat(output).isEqualTo("Graph Runtime 骨架已接收: 你好");
    }
}
