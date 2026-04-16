package com.limy.util;


import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MessageParamTest {

    @Test
    void testBlockList() throws IOException {

        List<ContentBlockParam> results = new ArrayList<>();
        results.add(ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                        .toolUseId("11112")
                        .content("output1")
                        .build()
        ));
        results.add(ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                        .toolUseId("22222")
                        .content("output2")
                        .build()
        ));
        MessageParam param = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofBlockParams(results))
                .build();


        List<ContentBlockParam> blockParams = param.content().asBlockParams();
        IO.println(blockParams.getClass());

        System.out.println(JsonValue.from(param));

        JsonMapper mapper = JsonMapper.builder().build();
        System.out.println(mapper.writer().writeValueAsString(param));

        String s = mapper.writer().writeValueAsString(param);
        MessageParam param1 = mapper.reader().readValue(s, MessageParam.class);
        System.out.println(param1);


        MessageParam param2 = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofBlockParams(results))
                .build();

        List<MessageParam> messageParams = new ArrayList<>();
        messageParams.add(param);
        messageParams.add(param2);

        System.out.println(mapper.writeValueAsString(messageParams));
        List<MessageParam> messageParams1 = mapper.readValue(mapper.writeValueAsString(messageParams), new TypeReference<List<MessageParam>>() {
        });
        System.out.println(messageParams1);

    }
}
