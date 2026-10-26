-- 划线提问并入正常对话：用户消息可携带一段划线引用（kbId、pageId、页面标题与引用文本），
-- 历史回显与多轮上下文据此还原；未带引用的消息为 NULL。
ALTER TABLE chat_message
    ADD COLUMN quote_json JSON NULL AFTER content;

-- 独立的划词问答链路已下线，其模型用途不再存在；旧行加载时本就会被忽略，这里一并清理。
DELETE FROM model_assignment WHERE purpose = 'SELECTION_QUESTION';
