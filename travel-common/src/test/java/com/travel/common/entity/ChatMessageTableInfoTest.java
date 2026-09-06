package com.travel.common.entity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F25 regression test: ChatMessage entity must align with t_chat_message table.
 *
 * <p>Guards the TC-10 fix: the MyBatis-Plus generated INSERT must not contain
 * updated_at (the table has no such column), while non-inherited fields keep
 * their full mapping.</p>
 */
class ChatMessageTableInfoTest {

    @Test
    void chatMessageTableInfo_shouldNotContainUpdatedAt() {
        TableInfo tableInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                ChatMessage.class);

        assertThat(tableInfo.getTableName()).isEqualTo("t_chat_message");
        assertThat(tableInfo.getKeyProperty()).isEqualTo("id");

        List<TableFieldInfo> fields = tableInfo.getFieldList();
        List<String> properties = fields.stream().map(TableFieldInfo::getProperty).toList();
        List<String> columns = fields.stream().map(TableFieldInfo::getColumn).toList();

        assertThat(properties)
                .contains("createdAt", "sessionId", "role", "content", "tokens")
                .doesNotContain("updatedAt");
        assertThat(columns)
                .contains("created_at", "session_id", "role", "content", "tokens")
                .doesNotContain("updated_at");
    }
}
