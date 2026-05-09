# 支持的厂商与模型

所有厂商均使用 **OpenAI 兼容协议**，统一通过 `/chat/completions` 端点调用。

## 1. DeepSeek
首次调用 API
DeepSeek API 使用与 OpenAI/Anthropic 兼容的 API 格式，通过修改配置，您可以使用 OpenAI/Anthropic SDK 来访问 DeepSeek API，或使用与 OpenAI/Anthropic API 兼容的软件。

PARAM	VALUE
base_url (OpenAI)	https://api.deepseek.com
base_url (Anthropic)	https://api.deepseek.com/anthropic
api_key	apply for an API key
model*	deepseek-v4-flash
deepseek-v4-pro
deepseek-chat (将于 2026/07/24 弃用)
deepseek-reasoner (将于 2026/07/24 弃用)
* deepseek-chat 与 deepseek-reasoner 两个模型名将于 2026/07/24 弃用。出于兼容考虑，二者分别对应 deepseek-v4-flash 的非思考与思考模式。

接入 Agent 工具
DeepSeek API 已接入多种主流 AI Agent 与编程助手工具。如果你使用 Claude Code、GitHub Copilot、OpenCode 等工具，可以直接将 DeepSeek 作为后端模型，无需编写代码即可开始使用。

详见 Agent 工具接入指南。

调用对话 API
在创建 API key 之后，你可以使用以下样例脚本，通过 OpenAI API 格式来访问 DeepSeek 模型。样例为非流式输出，您可以将 stream 设置为 true 来使用流式输出。

Anthropic API 格式的访问样例，请参考Anthropic API。

curl
python
nodejs
curl https://api.deepseek.com/chat/completions \
-H "Content-Type: application/json" \
-H "Authorization: Bearer ${DEEPSEEK_API_KEY}" \
-d '{
"model": "deepseek-v4-pro",
"messages": [
{"role": "system", "content": "You are a helpful assistant."},
{"role": "user", "content": "Hello!"}
],
"thinking": {"type": "enabled"},
"reasoning_effort": "high",
"stream": false
}'

思考模式
DeepSeek 模型支持思考模式：在输出最终回答之前，模型会先输出一段思维链内容，以提升最终答案的准确性。

思考模式开关与思考强度控制
控制参数（OpenAI 格式）	控制参数（Anthropic 格式）
思考模式开关(1)	{"thinking": {"type": "enabled/disabled"}}
思考强度控制(2)(3)	{"reasoning_effort": "high/max"}	{"output_config": {"effort": "high/max"}}
(1) 默认思考开关为 enabled
(2) 思考模式下，对普通请求，默认 effort 为 high；对一些复杂 Agent 类请求（如 Claude Code、OpenCode），effort 自动设置为 max
(3) 思考模式下，出于兼容考虑 low、medium 会映射为 high, xhigh 会映射为 max

您在使用 OpenAI SDK 设置 thinking 参数时，需要将 thinking 参数传入 extra_body 中：

response = client.chat.completions.create(
model="deepseek-v4-pro",
# ...
reasoning_effort="high",
extra_body={"thinking": {"type": "enabled"}}
)

输入输出参数
思考模式不支持 temperature、top_p、presence_penalty、frequency_penalty 参数。请注意，为了兼容已有软件，设置参数不会报错，但也不会生效。

在思考模式下，思维链内容通过 reasoning_content 参数返回，与 content 同级。在后续的轮次的拼接中，可以选择性地返回 reasoning_content 给 API：

在两个 user 消息之间，如果模型未进行工具调用，则中间 assistant 的 reasoning_content 无需参与上下文拼接，在后续轮次中将其传入 API 会被忽略。详见多轮对话拼接。
在两个 user 消息之间，如果模型进行了工具调用，则中间 assistant 的 reasoning_content 需参与上下文拼接，在后续所有 user 交互轮次中必须回传给 API。详见工具调用。
多轮对话拼接
在每一轮对话过程中，模型会输出思维链内容（reasoning_content）和最终回答（content）。如果没有工具调用，则在下一轮对话中，之前轮输出的思维链内容不会被拼接到上下文中，如下图所示：


样例代码
下面的代码以 Python 语言为例，展示了如何访问思维链和最终回答，以及如何在多轮对话中进行上下文拼接。

非流式
流式
from openai import OpenAI
client = OpenAI(api_key="<DeepSeek API Key>", base_url="https://api.deepseek.com")

# Turn 1
messages = [{"role": "user", "content": "9.11 and 9.8, which is greater?"}]
response = client.chat.completions.create(
model="deepseek-v4-pro",
messages=messages,
reasoning_effort="high"
extra_body={"thinking": {"type": "enabled"}},
)

reasoning_content = response.choices[0].message.reasoning_content
content = response.choices[0].message.content

# Turn 2
# The reasoning_content will be ignored by the API
messages.append(response.choices[0].message)
messages.append({'role': 'user', 'content': "How many Rs are there in the word 'strawberry'?"})
response = client.chat.completions.create(
model="deepseek-v4-pro",
messages=messages,
reasoning_effort="high"
extra_body={"thinking": {"type": "enabled"}},
)
# ...

工具调用
DeepSeek 模型的思考模式支持工具调用功能。模型在输出最终答案之前，可以进行多轮的思考与工具调用，以提升答案的质量。其调用模式如下图所示：


请注意，区别于思考模式下的未进行工具调用的轮次，进行了工具调用的轮次，在后续所有请求中，必须完整回传 reasoning_content 给 API。

若您的代码中未正确回传 reasoning_content，API 会返回 400 报错。正确回传方法请您参考下面的样例代码。

样例代码
下面是一个简单的在思考模式下进行工具调用的样例代码：

import os
import json
from openai import OpenAI
from datetime import datetime

# The definition of the tools
tools = [
{
"type": "function",
"function": {
"name": "get_date",
"description": "Get the current date",
"parameters": { "type": "object", "properties": {} },
}
},
{
"type": "function",
"function": {
"name": "get_weather",
"description": "Get weather of a location, the user should supply the location and date.",
"parameters": {
"type": "object",
"properties": {
"location": { "type": "string", "description": "The city name" },
"date": { "type": "string", "description": "The date in format YYYY-mm-dd" },
},
"required": ["location", "date"]
},
}
},
]

# The mocked version of the tool calls
def get_date_mock():
return datetime.now().strftime("%Y-%m-%d")

def get_weather_mock(location, date):
return "Cloudy 7~13°C"

TOOL_CALL_MAP = {
"get_date": get_date_mock,
"get_weather": get_weather_mock
}

def run_turn(turn, messages):
sub_turn = 1
while True:
response = client.chat.completions.create(
model='deepseek-v4-pro',
messages=messages,
tools=tools,
reasoning_effort="high",
extra_body={ "thinking": { "type": "enabled" } },
)
messages.append(response.choices[0].message)
reasoning_content = response.choices[0].message.reasoning_content
content = response.choices[0].message.content
tool_calls = response.choices[0].message.tool_calls
print(f"Turn {turn}.{sub_turn}\n{reasoning_content=}\n{content=}\n{tool_calls=}")
# If there is no tool calls, then the model should get a final answer and we need to stop the loop
if tool_calls is None:
break
for tool in tool_calls:
tool_function = TOOL_CALL_MAP[tool.function.name]
tool_result = tool_function(**json.loads(tool.function.arguments))
print(f"tool result for {tool.function.name}: {tool_result}\n")
messages.append({
"role": "tool",
"tool_call_id": tool.id,
"content": tool_result,
})
sub_turn += 1
print()

client = OpenAI(
api_key=os.environ.get('DEEPSEEK_API_KEY'),
base_url=os.environ.get('DEEPSEEK_BASE_URL'),
)

# The user starts a question
turn = 1
messages = [{
"role": "user",
"content": "How's the weather in Hangzhou Tomorrow"
}]
run_turn(turn, messages)

# The user starts a new question
turn = 2
messages.append({
"role": "user",
"content": "How's the weather in Guangzhou Tomorrow"
})
run_turn(turn, messages)


在 Turn 1 的每个子请求中，都携带了该 Turn 下产生的 reasoning_content 给 API，从而让模型继续之前的思考。response.choices[0].message 携带了 assistant 消息的所有必要字段，包括 content、reasoning_content、tool_calls。简单起见，可以直接用如下代码将消息 append 到 messages 结尾：

messages.append(response.choices[0].message)

这行代码等价于：

messages.append({
'role': 'assistant',
'content': response.choices[0].message.content,
'reasoning_content': response.choices[0].message.reasoning_content,
'tool_calls': response.choices[0].message.tool_calls,
})

且在 Turn 2 的请求中，我们仍然携带着 Turn1 所产生的 reasoning_content 给 API。

该代码的样例输出如下：

Turn 1.1
reasoning_content="The user is asking about the weather in Hangzhou tomorrow. I need to get tomorrow's date first, then call the weather function."
content="Let me check tomorrow's weather in Hangzhou for you. First, let me get tomorrow's date."
tool_calls=[ChatCompletionMessageFunctionToolCall(id='call_00_kw66qNnNto11bSfJVIdlV5Oo', function=Function(arguments='{}', name='get_date'), type='function', index=0)]
tool result for get_date: 2026-04-19

Turn 1.2
reasoning_content="Today is 2026-04-19, so tomorrow is 2026-04-20. Now I'll call the weather function for Hangzhou."
content=''
tool_calls=[ChatCompletionMessageFunctionToolCall(id='call_00_H2SCW6136vWJGq9SQlBuhVt4', function=Function(arguments='{"location": "Hangzhou", "date": "2026-04-20"}', name='get_weather'), type='function', index=0)]
tool result for get_weather: Cloudy 7~13°C

Turn 1.3
reasoning_content='The weather result is in. Let me share this with the user.'
content="Here's the weather forecast for **Hangzhou tomorrow (April 20, 2026)**:\n\n- 🌤 **Condition:** Cloudy  \n- 🌡 **Temperature:** 7°C ~ 13°C (45°F ~ 55°F)\n\nIt'll be on the cooler side, so you might want to bring a light jacket if you're heading out! Let me know if you need anything else."
tool_calls=None

Turn 2.1
reasoning_content='The user is asking about the weather in Guangzhou tomorrow. Today is 2026-04-19, so tomorrow is 2026-04-20. I can directly call the weather function.'
content=''
tool_calls=[ChatCompletionMessageFunctionToolCall(id='call_00_8URkLt5NjmNkVKhDmMcNq9Mo', function=Function(arguments='{"location": "Guangzhou", "date": "2026-04-20"}', name='get_weather'), type='function', index=0)]
tool result for get_weather: Cloudy 7~13°C

Turn 2.2
reasoning_content='The weather result for Guangzhou is the same as Hangzhou. Let me share this with the user.'
content="Here's the weather forecast for **Guangzhou tomorrow (April 20, 2026)**:\n\n- 🌤 **Condition:** Cloudy  \n- 🌡 **Temperature:** 7°C ~ 13°C (45°F ~ 55°F)\n\nIt'll be cool and cloudy, so a light jacket would be a good idea if you're going out. Let me know if there's anything else you'd like to know!"
tool_calls=None


多轮对话
本指南将介绍如何使用 DeepSeek /chat/completions API 进行多轮对话。

DeepSeek /chat/completions API 是一个“无状态” API，即服务端不记录用户请求的上下文，用户在每次请求时，需将之前所有对话历史拼接好后，传递给对话 API。

下面的代码以 Python 语言，展示了如何进行上下文拼接，以实现多轮对话。

from openai import OpenAI
client = OpenAI(api_key="<DeepSeek API Key>", base_url="https://api.deepseek.com")

# Round 1
messages = [{"role": "user", "content": "What's the highest mountain in the world?"}]
response = client.chat.completions.create(
model="deepseek-v4-pro",
messages=messages
)

messages.append(response.choices[0].message)
print(f"Messages Round 1: {messages}")

# Round 2
messages.append({"role": "user", "content": "What is the second?"})
response = client.chat.completions.create(
model="deepseek-v4-pro",
messages=messages
)

messages.append(response.choices[0].message)
print(f"Messages Round 2: {messages}")

在第一轮请求时，传递给 API 的 messages 为：

[
{"role": "user", "content": "What's the highest mountain in the world?"}
]

在第二轮请求时：

要将第一轮中模型的输出添加到 messages 末尾
将新的提问添加到 messages 末尾
最终传递给 API 的 messages 为：

[
{"role": "user", "content": "What's the highest mountain in the world?"},
{"role": "assistant", "content": "The highest mountain in the world is Mount Everest."},
{"role": "user", "content": "What is the second?"}
]
---

## 2. 通义千问 (Qwen)
本文介绍如何通过兼容 OpenAI 格式的 Chat API 调用千问模型，包括输入输出参数说明及调用示例。

## 华北2（北京）

SDK 调用配置的`base_url`：`https://dashscope.aliyuncs.com/compatible-mode/v1`

HTTP 请求地址：`POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`

## 新加坡

SDK 调用配置的`base_url`：`https://dashscope-intl.aliyuncs.com/compatible-mode/v1`

HTTP 请求地址：`POST https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions`

## 美国（弗吉尼亚）

SDK 调用配置的`base_url`：`https://dashscope-us.aliyuncs.com/compatible-mode/v1`

HTTP 请求地址：`POST https://dashscope-us.aliyuncs.com/compatible-mode/v1/chat/completions`

## 德国（法兰克福）

SDK 调用配置的`base_url`：`https://{WorkspaceId}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1`

HTTP 请求地址：`POST https://{WorkspaceId}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions`

调用时请将`WorkspaceId`替换为真实的[Workspace ID](https://help.aliyun.com/zh/model-studio/obtain-the-app-id-and-workspace-id#d3eb3cd37b7fu)。

> 您需要先[获取API Key](https://help.aliyun.com/zh/model-studio/get-api-key)并[配置API Key到环境变量](https://help.aliyun.com/zh/model-studio/configure-api-key-through-environment-variables)。若通过OpenAI SDK进行调用，需要[安装SDK](https://help.aliyun.com/zh/model-studio/install-sdk)。

| ## **请求体** | .api-test-trigger-wrapper { width: 100%; max-width: 100%; display: inline-flex; align-items: center; gap: 10px; background: #f5f7fa; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px 6px 6px 12px; margin-top: 26px; } .api-test-method { background: #e8f1fd; color: #1366EC; padding: 4px 8px; border-radius: 4px; font-weight: 600; font-size: 12px; letter-spacing: 0.02em; flex-shrink: 0; } .api-test-path { color: #475569; font-family: 'SF Mono', 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; font-size: 13px; font-weight: 400; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-right: 12px; } .api-test-trigger { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 6px 14px; background: #1366EC; color: white; border: none; border-radius: 4px; font-size: 13px; font-weight: 500; cursor: pointer; transition: all 0.15s; flex-shrink: 0; } .api-test-trigger:hover { background: #0f5ad8; } .api-test-trigger:active { background: #0d4fc2; } .api-test-trigger svg { width: 14px; height: 14px; } .api-test-modal-overlay { display: none; position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, 0.6); z-index: 999999; animation: fadeIn 0.2s ease; } .api-test-modal-overlay.active { display: flex; justify-content: center; align-items: center; padding: 20px; } @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } } .api-test-widget { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif; max-width: 1400px; width: 100%; max-height: 90vh; overflow-y: auto; padding: 8px 20px 16px; background: #ffffff; border-radius: 16px; box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.08); position: relative; animation: slideIn 0.3s ease; } @keyframes slideIn { from { opacity: 0; transform: translateY(-20px); } to { opacity: 1; transform: translateY(0); } } .api-test-widget \\* { box-sizing: border-box; } .api-test-widget .widget-header { display: flex; justify-content: space-between; align-items: center; margin: 0; margin-bottom: 8px; padding: 0; padding-bottom: 8px; border-bottom: 1px solid #f0f0f0; } .api-test-widget .widget-title { color: #1a1a1a; margin: 0 !important; padding: 0 !important; font-size: 16px; font-weight: 600; letter-spacing: -0.01em; line-height: 1.4; } .api-test-widget .close-btn { width: 28px; height: 28px; border: none; background: #f7f8fa; border-radius: 2px; cursor: pointer; font-size: 18px; line-height: 1; color: #8e8e93; transition: all 0.2s; display: flex; align-items: center; justify-content: center; } .api-test-widget .close-btn:hover { background: #e8e9ed; color: #1a1a1a; transform: scale(1.05); } .api-test-widget .form-group { margin-bottom: 12px; } .api-test-widget label { display: block; margin-bottom: 6px; color: #3c3c43; font-weight: 500; font-size: 14px; letter-spacing: -0.01em; } .api-test-widget .input-with-toggle { position: relative; } .api-test-widget input\\[type="text"\\] { width: 100%; padding: 10px 12px; padding-right: 45px; border: 1.5px solid #e5e5ea; border-radius: 2px; font-size: 14px; background: #fafafa; color: #1a1a1a; transition: all 0.2s; } .api-test-widget input\\[type="text"\\].password-hidden { -webkit-text-security: disc; text-security: disc; font-family: text-security-disc; } .api-test-widget input:focus { outline: none; border-color: #667eea; background: #ffffff; box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1); } .api-test-widget .toggle-password { position: absolute; right: 8px; top: 50%; transform: translateY(-50%); background: none; border: none; cursor: pointer; padding: 6px; color: #8e8e93; font-size: 12px; transition: all 0.2s; display: flex; align-items: center; gap: 4px; font-weight: 500; border-radius: 6px; } .api-test-widget .toggle-password:hover { color: #667eea; background: #f7f8fa; } .api-test-widget .toggle-password svg { width: 16px; height: 16px; } .api-test-widget textarea { width: 100%; max-width: 100%; padding: 10px 12px; border: 1.5px solid #e5e5ea; border-radius: 2px; font-size: 14px; line-height: 1.6; font-family: 'SF Mono', 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; resize: vertical; min-height: 200px; background: #fafafa; color: #1a1a1a; transition: all 0.2s; overflow-x: auto; white-space: pre; } /\\* 优化 textarea 滚动条样式 \\*/ .api-test-widget textarea::-webkit-scrollbar { width: 8px; height: 8px; } .api-test-widget textarea::-webkit-scrollbar-track { background: transparent; } .api-test-widget textarea::-webkit-scrollbar-thumb { background: #d1d1d6; border-radius: 4px; } .api-test-widget textarea::-webkit-scrollbar-thumb:hover { background: #b8b8bd; } .api-test-widget textarea:focus { outline: none; border-color: #667eea; background: #ffffff; box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1); } .api-test-widget .button-group { display: flex; gap: 10px; margin-bottom: 0; margin-top: -4px; } .api-test-widget button { padding: 10px 20px; border: none; border-radius: 2px; font-size: 14px; font-weight: 500; cursor: pointer; transition: all 0.2s; letter-spacing: -0.01em; } .api-test-widget .btn-primary { background: #1366EC; color: white; flex: 1; box-shadow: none; } .api-test-widget .btn-primary:hover { background: #094EBD; transform: none; box-shadow: none; } .api-test-widget .btn-primary:active { background: #0D3D8C; transform: none; } .api-test-widget .btn-primary:disabled { background: #C3D7FA; cursor: not-allowed; transform: none; box-shadow: none; } .api-test-widget .btn-secondary { background: #f7f8fa; color: #3c3c43; border: 1.5px solid #e5e5ea; } .api-test-widget .btn-secondary:hover { background: #e8e9ed; border-color: #d1d1d6; } .api-test-widget .btn-secondary:active { transform: scale(0.98); } .api-test-widget .response-box { background: #fafafa; border: 1.5px solid #e5e5ea; border-radius: 2px; padding: 10px 12px; overflow-y: auto; overflow-x: auto; font-family: 'SF Mono', 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; font-size: 14px; line-height: 1.6; white-space: pre; word-wrap: normal; color: #3c3c43; transition: all 0.3s ease; position: relative; } /\\* 优化滚动条样式 \\*/ .api-test-widget .response-box::-webkit-scrollbar { width: 8px; height: 8px; } .api-test-widget .response-box::-webkit-scrollbar-track { background: transparent; } .api-test-widget .response-box::-webkit-scrollbar-thumb { background: #d1d1d6; border-radius: 4px; } .api-test-widget .response-box::-webkit-scrollbar-thumb:hover { background: #b8b8bd; } .api-test-widget .response-box.error { background: #fff5f7; border-color: #ffccd5; color: #d70015; } .api-test-widget .response-box.success { background: #fafafa; border-color: #e5e5ea; color: #3c3c43; } .api-test-widget .loading { display: inline-block; width: 14px; height: 14px; border: 2px solid #f3f3f3; border-top: 2px solid #667eea; border-radius: 50%; animation: api-test-spin 1s linear infinite; margin-right: 8px; vertical-align: middle; } @keyframes api-test-spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } } .api-test-widget .info-text { color: #999; font-size: 12px; margin-top: 6px; } .api-test-widget .console-link { display: block; margin-top: 8px; font-size: 14px; color: #1366EC; text-decoration: none; transition: color 0.2s; } .api-test-widget .console-link:hover { color: #094EBD; text-decoration: underline; } .api-test-widget .endpoint-display { background: #f7f8fa; padding: 10px 12px; border-radius: 2px; border: 1.5px solid #e5e5ea; font-family: 'SF Mono', 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; font-size: 14px; color: #3c3c43; margin-bottom: 10px; word-break: break-all; } .api-test-widget .endpoint-label { background: #e8f1fd; color: #1366EC; padding: 4px 8px; border-radius: 4px; font-weight: 600; font-size: 12px; letter-spacing: 0.02em; margin-right: 8px; } .api-test-widget .region-selector { margin-bottom: 8px; display: flex; gap: 8px; align-items: center; } .api-test-widget .region-selector label { margin: 0; font-size: 14px; color: #3c3c43; } .api-test-widget .region-selector input\\[type="radio"\\] { margin-right: 6px; accent-color: #667eea; } .api-test-widget .region-option { display: flex; align-items: center; cursor: pointer; padding: 6px 12px; border-radius: 2px; transition: all 0.2s; border: 1.5px solid transparent; } .api-test-widget .region-option:hover { background: #f7f8fa; border-color: #e5e5ea; } .api-test-widget .region-option input\\[type="radio"\\] { cursor: pointer; } /\\* API Key 区域 - 独立在顶部，宽度与左侧面板一致 \\*/ .api-test-widget .api-key-section { margin-bottom: 16px; max-width: calc((100% - 24px) / 2); } .api-test-widget .api-key-section label { display: flex; align-items: center; gap: 8px; } .api-test-widget .bearer-badge { display: inline-block; background: #eef0ff; color: #667eea; font-size: 12px; padding: 2px 8px; border-radius: 4px; font-weight: 500; font-family: 'SF Mono', 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; } /\\* 内容布局：左右两个框作为容器 \\*/ .api-test-widget .content-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; align-items: start; } .api-test-widget .left-panel, .api-test-widget .right-panel { display: flex; flex-direction: column; min-width: 0; } .api-test-widget .left-panel .form-group:first-child { margin-top: 0; } .api-test-widget .right-panel .form-group:first-child { margin-top: 0; } /\\* 左右两个文本框高度一致，形成容器 \\*/ .api-test-widget #apiTestRequestBody, .api-test-widget .response-box { height: 25vh !important; min-height: 320px !important; max-height: 25vh !important; resize: none !important; overflow-y: auto !important; overflow-x: auto !important; } /\\* 按钮组样式 \\*/ .api-test-widget .left-panel .button-group { margin-top: 8px; } /\\* 右侧面板响应框容器 \\*/ .api-test-widget .right-panel .form-group { display: flex; flex-direction: column; margin-bottom: 0; } /\\* 响应标题栏 \\*/ .api-test-widget .response-header { display: flex; gap: 12px; align-items: center; margin-bottom: 6px; height: 32px; min-height: 32px; } .api-test-widget .response-header label { margin: 0; display: block; line-height: 32px; } .api-test-widget .copy-btn { width: 28px; height: 28px; padding: 0; background: #f7f8fa; color: #6b7280; border: 1px solid #e5e5ea; border-radius: 6px; cursor: pointer; transition: all 0.15s ease; display: flex; align-items: center; justify-content: center; flex-shrink: 0; } .api-test-widget .copy-btn:hover:not(:disabled) { background: #e8e9ed; color: #1366EC; border-color: #d1d1d6; transform: scale(1.05); } .api-test-widget .copy-btn:active:not(:disabled) { transform: scale(0.95); } .api-test-widget .copy-btn:disabled { opacity: 0.3; cursor: not-allowed; } .api-test-widget .copy-btn.copied { background: #10b981; color: white; border-color: #10b981; } .api-test-widget .copy-btn svg { width: 15px; height: 15px; } .api-test-widget .view-toggle, .api-test-widget .template-toggle { display: flex; background: #f7f8fa; border: 1.5px solid #e5e5ea; border-radius: 6px; padding: 2px; gap: 2px; height: 28px; } .api-test-widget .view-toggle-btn, .api-test-widget .template-toggle-btn { padding: 0 10px; background: transparent; border: none; border-radius: 4px; font-size: 12px; font-weight: 500; color: #6b7280; cursor: pointer; transition: all 0.15s ease; white-space: nowrap; line-height: 22px; } .api-test-widget .view-toggle-btn:hover, .api-test-widget .template-toggle-btn:hover { color: #1a1a1a; } .api-test-widget .view-toggle-btn.active, .api-test-widget .template-toggle-btn.active { background: #1366EC; color: white; } .api-test-widget .view-toggle-btn:disabled, .api-test-widget .template-toggle-btn:disabled { pointer-events: none; } .api-test-widget .template-toggle { flex-wrap: wrap; height: auto; max-width: 100%; } .api-test-widget .template-toggle-btn { padding: 0 8px; font-size: 11px; } .api-test-widget .parsed-content { padding: 0; } .api-test-widget .parsed-section { margin-bottom: 16px; } .api-test-widget .parsed-section:last-child { margin-bottom: 0; } .api-test-widget .parsed-label { font-weight: 600; color: #1366EC; font-size: 13px; margin-bottom: 6px; display: block; } .api-test-widget .parsed-value { color: #3c3c43; font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; } .api-test-widget .parsed-empty { color: #999; font-style: italic; } POST /chat/completions 调试 ## OpenAI 兼容接口在线调试 × 中国大陆（北京） 国际（新加坡） POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions API Key Bearer Token [获取中国大陆（北京）地域 API Key](https://bailian.console.aliyun.com/?tab=model#/api-key) 请求体 默认 深度思考 图像输入 工具调用 结构化输出 { "model": "qwen-plus", "messages": \\[ { "role": "system", "content": "You are a helpful assistant." }, { "role": "user", "content": "你是谁？" } \\] } 发送请求 清空响应 响应结果 原始响应 解析内容 ## 文本输入 ## Python ``` import os from openai import OpenAI client = OpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) completion = client.chat.completions.create( # 模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models model="qwen-plus", messages=[ {"role": "system", "content": "You are a helpful assistant."}, {"role": "user", "content": "你是谁？"}, ] ) print(completion.model_dump_json()) ``` ## Java ``` // 该代码 OpenAI SDK 版本为 2.6.0 import com.openai.client.OpenAIClient; import com.openai.client.okhttp.OpenAIOkHttpClient; import com.openai.models.chat.completions.ChatCompletion; import com.openai.models.chat.completions.ChatCompletionCreateParams; public class Main { public static void main(String[] args) { OpenAIClient client = OpenAIOkHttpClient.builder() .apiKey(System.getenv("DASHSCOPE_API_KEY")) .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1") .build(); ChatCompletionCreateParams params = ChatCompletionCreateParams.builder() .addUserMessage("你是谁") .model("qwen-plus") .build(); try { ChatCompletion chatCompletion = client.chat().completions().create(params); System.out.println(chatCompletion); } catch (Exception e) { System.err.println("Error occurred: " + e.getMessage()); e.printStackTrace(); } } } ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI( { // 若没有配置环境变量，请用百炼API Key将下行替换为：apiKey: "sk-xxx", apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" } ); async function main() { const completion = await openai.chat.completions.create({ model: "qwen-plus", //此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages: [ { role: "system", content: "You are a helpful assistant." }, { role: "user", content: "你是谁？" } ], }); console.log(JSON.stringify(completion)) } main(); ``` ## Go ``` package main import ( "context" "os" "github.com/openai/openai-go" "github.com/openai/openai-go/option" ) func main() { client := openai.NewClient( option.WithAPIKey(os.Getenv("DASHSCOPE_API_KEY")), option.WithBaseURL("https://dashscope.aliyuncs.com/compatible-mode/v1"), ) chatCompletion, err := client.Chat.Completions.New( context.TODO(), openai.ChatCompletionNewParams{ Messages: []openai.ChatCompletionMessageParamUnion{ openai.UserMessage("你是谁"), }, Model: "qwen-plus", }, ) if err != nil { panic(err.Error()) } println(chatCompletion.Choices[0].Message.Content) } ``` ## C#（HTTP） ``` using System.Net.Http.Headers; using System.Text; class Program { private static readonly HttpClient httpClient = new HttpClient(); static async Task Main(string[] args) { // 若没有配置环境变量，请用百炼API Key将下行替换为：string? apiKey = "sk-xxx"; string? apiKey = Environment.GetEnvironmentVariable("DASHSCOPE_API_KEY"); if (string.IsNullOrEmpty(apiKey)) { Console.WriteLine("API Key 未设置。请确保环境变量 'DASHSCOPE_API_KEY' 已设置。"); return; } // 设置请求 URL 和内容 string url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"; // 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models string jsonContent = @"{ ""model"": ""qwen-plus"", ""messages"": [ { ""role"": ""system"", ""content"": ""You are a helpful assistant."" }, { ""role"": ""user"", ""content"": ""你是谁？"" } ] }"; // 发送请求并获取响应 string result = await SendPostRequestAsync(url, jsonContent, apiKey); // 输出结果 Console.WriteLine(result); } private static async Task<string> SendPostRequestAsync(string url, string jsonContent, string apiKey) { using (var content = new StringContent(jsonContent, Encoding.UTF8, "application/json")) { // 设置请求头 httpClient.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", apiKey); httpClient.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json")); // 发送请求并获取响应 HttpResponseMessage response = await httpClient.PostAsync(url, content); // 处理响应 if (response.IsSuccessStatusCode) { return await response.Content.ReadAsStringAsync(); } else { return $"请求失败: {response.StatusCode}"; } } } } ``` ## PHP（HTTP） ``` <?php // 设置请求的URL $url = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'; // 若没有配置环境变量，请用百炼API Key将下行替换为：$apiKey = "sk-xxx"; $apiKey = getenv('DASHSCOPE_API_KEY'); // 设置请求头 $headers = [ 'Authorization: Bearer '.$apiKey, 'Content-Type: application/json' ]; // 设置请求体 $data = [ // 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models "model" => "qwen-plus", "messages" => [ [ "role" => "system", "content" => "You are a helpful assistant." ], [ "role" => "user", "content" => "你是谁？" ] ] ]; // 初始化cURL会话 $ch = curl_init(); // 设置cURL选项 curl_setopt($ch, CURLOPT_URL, $url); curl_setopt($ch, CURLOPT_POST, true); curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($data)); curl_setopt($ch, CURLOPT_RETURNTRANSFER, true); curl_setopt($ch, CURLOPT_HTTPHEADER, $headers); // 执行cURL会话 $response = curl_exec($ch); // 检查是否有错误发生 if (curl_errno($ch)) { echo 'Curl error: ' . curl_error($ch); } // 关闭cURL资源 curl_close($ch); // 输出响应结果 echo $response; ?> ``` ## curl ``` curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H "Content-Type: application/json" \\ -d '{ "model": "qwen-plus", "messages": [ { "role": "system", "content": "You are a helpful assistant." }, { "role": "user", "content": "你是谁？" } ] }' ``` ## 流式输出 > 相关文档：[流式输出](https://help.aliyun.com/zh/model-studio/stream)。 ## Python ``` import os from openai import OpenAI client = OpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) completion = client.chat.completions.create( model="qwen-plus", # 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages=[{'role': 'system', 'content': 'You are a helpful assistant.'}, {'role': 'user', 'content': '你是谁？'}], stream=True, stream_options={"include_usage": True} ) for chunk in completion: print(chunk.model_dump_json()) ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI( { // 若没有配置环境变量，请用百炼API Key将下行替换为：apiKey: "sk-xxx", apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" } ); async function main() { const completion = await openai.chat.completions.create({ model: "qwen-plus", // 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages: [ {"role": "system", "content": "You are a helpful assistant."}, {"role": "user", "content": "你是谁？"} ], stream: true, stream_options: {include_usage: true} }); for await (const chunk of completion) { console.log(JSON.stringify(chunk)); } } main(); ``` ## curl ``` curl --location "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" \\ --header "Authorization: Bearer $DASHSCOPE_API_KEY" \\ --header "Content-Type: application/json" \\ --data '{ "model": "qwen-plus", "messages": [ { "role": "system", "content": "You are a helpful assistant." }, { "role": "user", "content": "你是谁？" } ], "stream":true, "stream_options": { "include_usage": true } }' ``` ## 图像输入 > 相关文档：[图像与视频理解](https://help.aliyun.com/zh/model-studio/vision)。 ## Python ``` import os from openai import OpenAI client = OpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) completion = client.chat.completions.create( model="qwen-vl-plus", # 此处以qwen-vl-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages=[{"role": "user","content": [ {"type": "image_url", "image_url": {"url": "https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"}}, {"type": "text", "text": "这是什么"}, ]}] ) print(completion.model_dump_json()) ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI( { // 若没有配置环境变量，请用百炼API Key将下行替换为：apiKey: "sk-xxx", apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" } ); async function main() { const response = await openai.chat.completions.create({ model: "qwen-vl-max", // 此处以qwen-vl-max为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages: [{role: "user",content: [ { type: "image_url",image_url: {"url": "https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"}}, { type: "text", text: "这是什么？" }, ]}] }); console.log(JSON.stringify(response)); } main(); ``` ## curl ``` curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H 'Content-Type: application/json' \\ -d '{ "model": "qwen-vl-plus", "messages": [{ "role": "user", "content": [ {"type": "image_url","image_url": {"url": "https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"}}, {"type": "text","text": "这是什么"} ]}] }' ``` ## 视频输入 > 以下示例展示了如何将图片列表作为视频输入。如需使用视频文件等其他方式，请参阅“[视觉理解](https://help.aliyun.com/zh/model-studio/vision#80dbf6ca8fh6s)。 ## Python ``` import os from openai import OpenAI client = OpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) completion = client.chat.completions.create( # 此处以qwen-vl-max-latest为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models model="qwen-vl-max-latest", messages=[{ "role": "user", "content": [ { "type": "video", "video": [ "https://img.alicdn.com/imgextra/i3/O1CN01K3SgGo1eqmlUgeE9b_!!6000000003923-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i4/O1CN01BjZvwg1Y23CF5qIRB_!!6000000003000-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i4/O1CN01Ib0clU27vTgBdbVLQ_!!6000000007859-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i1/O1CN01aygPLW1s3EXCdSN4X_!!6000000005710-0-tps-3840-2160.jpg"] }, { "type": "text", "text": "描述这个视频的具体过程" }]}] ) print(completion.model_dump_json()) ``` ## Node.js ``` // 确保之前在 package.json 中指定了 "type": "module" import OpenAI from "openai"; const openai = new OpenAI({ // 若没有配置环境变量，请用百炼API Key将下行替换为：apiKey: "sk-xxx", apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" }); async function main() { const response = await openai.chat.completions.create({ // 此处以qwen-vl-max-latest为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models model: "qwen-vl-max-latest", messages: [{ role: "user", content: [ { type: "video", video: [ "https://img.alicdn.com/imgextra/i3/O1CN01K3SgGo1eqmlUgeE9b_!!6000000003923-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i4/O1CN01BjZvwg1Y23CF5qIRB_!!6000000003000-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i4/O1CN01Ib0clU27vTgBdbVLQ_!!6000000007859-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i1/O1CN01aygPLW1s3EXCdSN4X_!!6000000005710-0-tps-3840-2160.jpg" ] }, { type: "text", text: "描述这个视频的具体过程" } ]}] }); console.log(JSON.stringify(response)); } main(); ``` ## curl ``` curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H 'Content-Type: application/json' \\ -d '{ "model": "qwen-vl-max-latest", "messages": [ { "role": "user", "content": [ { "type": "video", "video": [ "https://img.alicdn.com/imgextra/i3/O1CN01K3SgGo1eqmlUgeE9b_!!6000000003923-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i4/O1CN01BjZvwg1Y23CF5qIRB_!!6000000003000-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i4/O1CN01Ib0clU27vTgBdbVLQ_!!6000000007859-0-tps-3840-2160.jpg", "https://img.alicdn.com/imgextra/i1/O1CN01aygPLW1s3EXCdSN4X_!!6000000005710-0-tps-3840-2160.jpg" ] }, { "type": "text", "text": "描述这个视频的具体过程" } ] } ] }' ``` ## 工具调用 > 相关文档：[Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling) ## Python ``` import os from openai import OpenAI client = OpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", # 填写DashScope SDK的base_url ) tools = [ # 工具1 获取当前时刻的时间 { "type": "function", "function": { "name": "get_current_time", "description": "当你想知道现在的时间时非常有用。", "parameters": {} # 因为获取当前时间无需输入参数，因此parameters为空字典 } }, # 工具2 获取指定城市的天气 { "type": "function", "function": { "name": "get_current_weather", "description": "当你想查询指定城市的天气时非常有用。", "parameters": { "type": "object", "properties": { # 查询天气时需要提供位置，因此参数设置为location "location": { "type": "string", "description": "城市或县区，比如北京市、杭州市、余杭区等。" } }, "required": ["location"] } } } ] messages = [{"role": "user", "content": "杭州天气怎么样"}] completion = client.chat.completions.create( model="qwen-plus", # 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages=messages, tools=tools ) print(completion.model_dump_json()) ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI( { // 若没有配置环境变量，请用百炼API Key将下行替换为：apiKey: "sk-xxx", apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" } ); const messages = [{"role": "user", "content": "杭州天气怎么样"}]; const tools = [ // 工具1 获取当前时刻的时间 { "type": "function", "function": { "name": "get_current_time", "description": "当你想知道现在的时间时非常有用。", // 因为获取当前时间无需输入参数，因此parameters为空 "parameters": {} } }, // 工具2 获取指定城市的天气 { "type": "function", "function": { "name": "get_current_weather", "description": "当你想查询指定城市的天气时非常有用。", "parameters": { "type": "object", "properties": { // 查询天气时需要提供位置，因此参数设置为location "location": { "type": "string", "description": "城市或县区，比如北京市、杭州市、余杭区等。" } }, "required": ["location"] } } } ]; async function main() { const response = await openai.chat.completions.create({ model: "qwen-plus", // 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages: messages, tools: tools, }); console.log(JSON.stringify(response)); } main(); ``` ## curl ``` curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H "Content-Type: application/json" \\ -d '{ "model": "qwen-plus", "messages": [ { "role": "system", "content": "You are a helpful assistant." }, { "role": "user", "content": "杭州天气怎么样" } ], "tools": [ { "type": "function", "function": { "name": "get_current_time", "description": "当你想知道现在的时间时非常有用。", "parameters": {} } }, { "type": "function", "function": { "name": "get_current_weather", "description": "当你想查询指定城市的天气时非常有用。", "parameters": { "type": "object", "properties": { "location":{ "type": "string", "description": "城市或县区，比如北京市、杭州市、余杭区等。" } }, "required": ["location"] } } } ] }' ``` ## 联网搜索 ## Python ``` import os from openai import OpenAI client = OpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) completion = client.chat.completions.create( model="qwen-plus", # 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages=[ {'role': 'system', 'content': 'You are a helpful assistant.'}, {'role': 'user', 'content': '中国队在巴黎奥运会获得了多少枚金牌'}], extra_body={ "enable_search": True } ) print(completion.model_dump_json()) ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI( { // 若没有配置环境变量，请用百炼API Key将下行替换为：apiKey: "sk-xxx", apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" } ); async function main() { const completion = await openai.chat.completions.create({ model: "qwen-plus", //此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages: [ { role: "system", content: "You are a helpful assistant." }, { role: "user", content: "中国队在巴黎奥运会获得了多少枚金牌" } ], enable_search:true }); console.log(JSON.stringify(completion)) } main(); ``` ## curl ``` curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H "Content-Type: application/json" \\ -d '{ "model": "qwen-plus", "messages": [ { "role": "system", "content": "You are a helpful assistant." }, { "role": "user", "content": "中国队在巴黎奥运会获得了多少枚金牌" } ], "enable_search": true }' ``` ## 异步调用 ``` import os import asyncio from openai import AsyncOpenAI import platform client = AsyncOpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) async def main(): response = await client.chat.completions.create( messages=[{"role": "user", "content": "你是谁"}], model="qwen-plus", # 此处以qwen-plus为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models ) print(response.model_dump_json()) if platform.system() == "Windows": asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy()) asyncio.run(main()) ``` ## 文档理解 > 当前仅qwen-long模型支持对文档进行分析，详细用法请参见[长上下文（Qwen-Long）](https://help.aliyun.com/zh/model-studio/long-context-qwen-long)。 ## Python ``` import os from pathlib import Path from openai import OpenAI client = OpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) file_object = client.files.create(file=Path("百炼系列手机产品介绍.docx"), purpose="file-extract") completion = client.chat.completions.create( model="qwen-long", # 模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages=[ {'role': 'system', 'content': f'fileid://{file_object.id}'}, {'role': 'user', 'content': '这篇文章讲了什么？'} ] ) print(completion.model_dump_json()) ``` ## Java ``` // 建议OpenAI SDK的版本 >= 0.32.0 import com.openai.client.OpenAIClient; import com.openai.client.okhttp.OpenAIOkHttpClient; import com.openai.models.chat.completions.ChatCompletion; import com.openai.models.chat.completions.ChatCompletionCreateParams; import com.openai.models.files.FileCreateParams; import com.openai.models.files.FileObject; import com.openai.models.files.FilePurpose; import java.nio.file.Path; import java.nio.file.Paths; public class Main { public static void main(String[] args) { // 创建客户端，使用环境变量中的API密钥 OpenAIClient client = OpenAIOkHttpClient.builder() .apiKey(System.getenv("DASHSCOPE_API_KEY")) .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1") .build(); // 设置文件路径 Path filePath = Paths.get("百炼系列手机产品介绍.docx"); // 创建文件上传参数 FileCreateParams fileParams = FileCreateParams.builder() .file(filePath) .purpose(FilePurpose.of("file-extract")) .build(); // 上传文件 FileObject fileObject = client.files().create(fileParams); String fileId = fileObject.id(); // 创建聊天请求 ChatCompletionCreateParams chatParams = ChatCompletionCreateParams.builder() .addSystemMessage("fileid://" + fileId) .addUserMessage("这篇文章讲了什么？") .model("qwen-long") .build(); // 发送请求并获取响应 ChatCompletion chatCompletion = client.chat().completions().create(chatParams); // 打印响应结果 System.out.println(chatCompletion); } } ``` ## Node.js ``` import fs from "fs"; import OpenAI from "openai"; const openai = new OpenAI( { // 若没有配置环境变量，请用百炼API Key将下行替换为：apiKey: "sk-xxx", apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" } ); async function getFileID() { const fileObject = await openai.files.create({ file: fs.createReadStream("百炼系列手机产品介绍.docx"), purpose: "file-extract" }); return fileObject.id; } async function main() { const fileID = await getFileID(); const completion = await openai.chat.completions.create({ model: "qwen-long", //模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models messages: [ { role: "system", content: `fileid://${fileID}`}, { role: "user", content: "这篇文章讲了什么？" } ], }); console.log(JSON.stringify(completion)) } main(); ``` ## curl ``` curl --location 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions' \\ --header "Authorization: Bearer $DASHSCOPE_API_KEY" \\ --header "Content-Type: application/json" \\ --data '{ "model": "qwen-long", "messages": [ {"role": "system","content": "You are a helpful assistant."}, {"role": "system","content": "fileid://file-fe-xxx"}, {"role": "user","content": "这篇文章讲了什么？"} ], "stream": true, "stream_options": { "include_usage": true } }' ``` ## PPT生成 > 当前仅`qwen-doc-turbo`模型支持PPT生成。详细用法请参见[生成PPT](https://help.aliyun.com/zh/model-studio/data-mining-qwen-doc#a1b2c3d4e5ppt)。 ## Python ``` import os from openai import OpenAI client = OpenAI( api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) completion = client.chat.completions.create( model="qwen-doc-turbo", messages=[ {"role": "system", "content": "you are a helpful assistant."}, {"role": "system", "content": "您的文档内容"}, {"role": "user", "content": "生成一个10到20页的ppt"} ], extra_body={"skill": [{"type": "ppt", "mode": "general", "template_id": "news_01"}]}, stream=True, stream_options={"include_usage": True} ) for chunk in completion: if chunk.choices and chunk.choices[0].delta.content: print(chunk.choices[0].delta.content, end='', flush=True) ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI( { apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" } ); async function main() { const completion = await openai.chat.completions.create({ model: "qwen-doc-turbo", messages: [ {"role": "system", "content": "you are a helpful assistant."}, {"role": "system", "content": "您的文档内容"}, {"role": "user", "content": "生成一个10到20页的ppt"} ], skill: [{"type": "ppt", "mode": "general", "template_id": "news_01"}], stream: true, stream_options: {"include_usage": true} }); for await (const chunk of completion) { if (chunk.choices?.length > 0 && chunk.choices[0].delta.content) { process.stdout.write(chunk.choices[0].delta.content); } } } main(); ``` ## curl ``` curl --location 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions' \\ --header "Authorization: Bearer $DASHSCOPE_API_KEY" \\ --header "Content-Type: application/json" \\ --data '{ "model": "qwen-doc-turbo", "messages": [ { "role": "system", "content": "you are a helpful assistant." }, { "role": "system", "content": "您的文档内容" }, { "role": "user", "content": "生成一个10到20页的ppt" } ], "skill": [ { "type": "ppt", "mode": "general", "template_id": "news_01" } ], "stream": true, "stream_options": { "include_usage": true } }' ``` |
| --- | --- |
| **model** `*string*` **（必选）** 模型名称。 支持的模型：Qwen 大语言模型（商业版、开源版）、Qwen-VL、Qwen-Coder、Qwen-Omni、Qwen-Math。 > Qwen-Audio不支持OpenAI兼容协议，仅支持DashScope协议。 **具体模型名称和计费，请参见**百炼控制台。 |
| **messages** `*array*` **（必选）** 传递给大模型的上下文，按对话顺序排列。 **消息类型** System Message `*object*` （可选） 系统消息，用于设定大模型的角色、语气、任务目标或约束条件等。一般放在`messages`数组的第一位。 > QwQ 模型不建议设置 System Message，QVQ 模型设置 System Message不会生效。 **属性** **content** `*string*` **（必选）** 系统指令，用于明确模型的角色、行为规范、回答风格和任务约束等。 **role** `*string*` **（必选）** 系统消息的角色，固定为`system`。 User Message `*object*` **（必选）** 用户消息，用于向模型传递问题、指令或上下文等。 **属性** **content** `*string 或 array*` **（必选）** 消息内容。若输入只有文本，则为 string 类型；若输入包含图像等多模态数据，或启用显式缓存，则为 array 类型。 **使用多模态模型或启用显式缓存时的属性** **type** `*string*` **（必选）** 可选值： - `text` 输入文本时需设为`text`。 - `image_url` 输入图片时需设为`image_url`。 - `input_audio` 输入音频时需设为`input_audio`。 - `video` 输入图片列表形式的视频时需设为`video`。 - `video_url` 输入视频文件时需设为`video_url`。 > Qwen-VL仅部分模型可输入视频文件，详情参见[视频理解（Qwen-VL）](https://help.aliyun.com/zh/model-studio/vision#80dbf6ca8fh6s)；QVQ与Qwen-Omni 模型支持直接传入视频文件。 **text** `*string*` 输入的文本。当`type`为`text`时，是必选参数。 **image\\_url** `*object*` 输入的图片信息。当`type`为`image_url`时是必选参数。 **属性** **url** `*string*`**（必选）** 图片的 URL或 Base64 Data URL。传入本地文件请参考[图像与视频理解](https://help.aliyun.com/zh/model-studio/vision#647c6397db430)。 **input\\_audio** `*object*` 输入的音频信息。当`type`为`input_audio`时是必选参数。 **属性** **data** `*string*`**（必选）** 音频的 URL 或Base64 Data URL。传入本地文件请参见：[输入 Base64 编码的本地文件](https://help.aliyun.com/zh/model-studio/qwen-omni#c516d1e824x03)。 **format** `*string*`**（必选）** 输入音频的格式，如`mp3`、`wav`等。 **video** `*array*` 输入的**图片列表形式的视频信息**。当`type`为`video`时是必选参数。使用方法请参见：[视频理解（Qwen-VL）](https://help.aliyun.com/zh/model-studio/vision#80dbf6ca8fh6s)、[视频理解（QVQ）](https://help.aliyun.com/zh/model-studio/visual-reasoning#e6df293d5565g)或[视频理解（Qwen-Omni）](https://help.aliyun.com/zh/model-studio/qwen-omni#0f4360d63a8nk)。 示例值： ``` [ "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241108/xzsgiz/football1.jpg", "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241108/tdescd/football2.jpg", "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241108/zefdja/football3.jpg", "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241108/aedbqh/football4.jpg" ] ``` **video\\_url** `*object*` 输入的视频文件信息。当`type`为`video_url`时是必选参数。 Qwen-VL 只可理解视频文件的视觉信息，Qwen-Omni 可理解视频文件中的视觉与音频信息。 **属性** **url** `*string*`**（必选）** 视频文件的公网 URL 或 Base64 Data URL。输入本地视频文件请参见[输入 Base64 编码的本地文件](https://help.aliyun.com/zh/model-studio/qwen-omni#c516d1e824x03)。 **fps** `*float*` （可选） 每秒抽帧数。取值范围为 \\[0.1, 10\\]，默认值为2.0。 **功能说明** fps有两个功能： - 输入视频文件时，控制抽帧频率，每 fps1​秒抽取一帧。 > 适用于 [Qwen-VL](https://help.aliyun.com/zh/model-studio/vision) 与[QVQ 模型](https://help.aliyun.com/zh/model-studio/visual-reasoning)。 - 告知模型相邻帧之间的时间间隔，帮助其更好地理解视频的时间动态。同时适用于输入视频文件与图像列表时。该功能同时支持视频文件和图像列表输入，适用于事件时间定位或分段内容摘要等场景。 > 支持Qwen3.6、Qwen3.5、`Qwen3-VL`、`Qwen2.5-VL`、Qwen3.5-Omni与QVQ模型。 较大的`fps`适合高速运动的场景（如体育赛事、动作电影等），较小的`fps`适合长视频或内容偏静态的场景。 **示例值** - 图像列表传入：`{"video":["https://xx1.jpg",...,"https://xxn.jpg"]，"fps":2}` - 视频文件传入：`{"video": "https://xx1.mp4"，"fps":2}` **min\\_pixels** `*integer*` （可选） 设定输入图像或视频帧的最小像素阈值。当输入图像或视频帧的像素小于`min_pixels`时，会将其进行放大，直到总像素高于`min_pixels`。适用于 Qwen-VL、QVQ 模型。 **取值范围** - **输入图像：** - `Qwen3.6`、`Qwen3.5`、`Qwen3-VL`：默认值和最小值均为：`65536` - `Qwen3.5-Omni`：默认值和最小值均为：`24576` - `qwen-vl-max`、`qwen-vl-max-latest`、`qwen-vl-max-0813`、`qwen-vl-plus`、`qwen-vl-plus-latest`、`qwen-vl-plus-0815``、qwen-vl-plus-0710`：默认值和最小值均为`4096` - 其他`qwen-vl-plus`模型、其他`qwen-vl-max`模型、`Qwen2.5-VL`开源系列及`QVQ`系列模型：默认值和最小值均为`3136` - **输入视频文件或图像列表：** - Qwen3.6、Qwen3.5、`Qwen3.5-Omni`、Qwen3-VL（包括商业版和开源版）、`qwen-vl-max`、`qwen-vl-max-latest`、`qwen-vl-max-0813`、`qwen-vl-plus`、`qwen-vl-plus-latest`、`qwen-vl-plus-0815``、qwen-vl-plus-0710`：默认值为`65536`，最小值为`4096` - 其他`qwen-vl-plus`模型、其他`qwen-vl-max`模型、`Qwen2.5-VL`开源系列及`QVQ`系列模型：默认值为`50176`，最小值为`3136` **示例值** - 输入图像：`{"type": "image_url","image_url": {"url":"https://xxxx.jpg"},"min_pixels": 65536}` - 输入视频文件时：`{"type": "video_url","video_url": {"url":"https://xxxx.mp4"},"min_pixels": 65536}` - 输入图像列表时：`{"type": "video","video": ["https://xx1.jpg",...,"https://xxn.jpg"],"min_pixels": 65536}` **max\\_pixels** `*integer*` （可选） 用于设定输入图像或视频帧的最大像素阈值。当输入图像或视频的像素在`[min_pixels, max_pixels]`区间内时，模型会按原图进行识别。当输入图像像素大于`max_pixels`时，会将图像进行缩小，直到总像素低于`max_pixels`。适用于 Qwen-VL、QVQ 模型。 **取值范围** - **输入图像：** `max_pixels` 的取值与是否开启`[vl_high_resolution_images](https://help.aliyun.com/zh/model-studio/qwen-api-reference/#0edad44583knr)`参数有关。 - 当`vl_high_resolution_images`为`False`时： - `Qwen3.6`、`Qwen3.5`、`Qwen3-VL`：默认值为`2621440`，最大值为：`16777216` - `Qwen3.5-Omni`：默认值为`1310720`，最大值为：`16777216` - `qwen-vl-max`、`qwen-vl-max-latest`、`qwen-vl-max-0813`、`qwen-vl-plus`、`qwen-vl-plus-latest`、`qwen-vl-plus-0815``、qwen-vl-plus-0710`：默认值为`1310720`，最大值为：`16777216` - 其他`qwen-vl-plus`模型、其他`qwen-vl-max`模型、`Qwen2.5-VL`开源系列及`QVQ`系列模型：默认值为`1003520` ，最大值为`12845056` - 当`vl_high_resolution_images`为`True`时： - `Qwen3.6`、`Qwen3.5-Omni`、`Qwen3.5`、`Qwen3-VL`、`qwen-vl-max`、`qwen-vl-max-latest`、`qwen-vl-max-0813`、`qwen-vl-plus`、`qwen-vl-plus-latest`、`qwen-vl-plus-0815``、qwen-vl-plus-0710`：`max_pixels`无效，输入图像的最大像素固定为`16777216` - 其他`qwen-vl-plus`模型、其他`qwen-vl-max`模型、`Qwen2.5-VL`开源系列及`QVQ`系列模型：`max_pixels`无效，输入图像的最大像素固定为`12845056` - **输入视频文件或图像列表：** - `Qwen3.6系列、``Qwen3.5系列、``Qwen3.5-Omni`、`Qwen3-VL闭源系列`、`qwen3-vl-235b-a22b-thinking`、`qwen3-vl-235b-a22b-instruct`：默认值为`655360`，最大值为`2048000` - 其他`Qwen3-VL`开源模型、`qwen-vl-max`、`qwen-vl-max-latest`、`qwen-vl-max-0813`、`qwen-vl-plus`、`qwen-vl-plus-latest`、`qwen-vl-plus-0815``、qwen-vl-plus-0710`：默认值`655360`，最大值为`786432` - 其他`qwen-vl-plus`模型、其他`qwen-vl-max`模型、`Qwen2.5-VL`开源系列及`QVQ`系列模型：默认值为`501760`，最大值为`602112` **示例值** - 输入图像：`{"type": "image_url","image_url": {"url":"https://xxxx.jpg"},"max_pixels": 8388608}` - 输入视频文件时：`{"type": "video_url","video_url": {"url":"https://xxxx.mp4"},"max_pixels": 655360}` - 输入图像列表时：`{"type": "video","video": ["https://xx1.jpg",...,"https://xxn.jpg"],"max_pixels": 655360}` **total\\_pixels** `*integer*` （可选） 用于限制从视频中抽取的所有帧的总像素（单帧图像像素 × 总帧数）。如果视频总像素超过此限制，系统将对视频帧进行缩放，但仍会确保单帧图像的像素值在`[min_pixels, max_pixels]`范围内。适用于 Qwen-VL、QVQ 模型。 对于抽帧数量较多的长视频，可适当降低此值以减少Token消耗和处理时间，但这可能会导致图像细节丢失。 **取值范围** - `Qwen3.6系列、``Qwen3.5系列`：默认值和最大值均为`819200000`，该值对应 `800000` 个图像 Token（每 32×32 像素对应 1 个图像 Token）。 - `Qwen3-VL闭源系列`、`qwen3-vl-235b-a22b-thinking`、`qwen3-vl-235b-a22b-instruct`：默认值和最大值均为`134217728`，该值对应 `131072` 个图像 Token（每 32×32 像素对应 1 个图像 Token）。 - `Qwen3.5-Omni`：默认值和最小值均为`184549376`，该值对应 `180224` 个图像 Token（每 32×32 像素对应 1 个图像 Token）。 - 其他`Qwen3-VL`开源模型、`qwen-vl-max`、`qwen-vl-max-latest`、`qwen-vl-max-0813`、`qwen-vl-plus`、`qwen-vl-plus-latest`、`qwen-vl-plus-0815``、qwen-vl-plus-0710`：默认值和最小值均为`67108864`，该值对应 `65536` 个图像 Token（每 32×32 像素对应 1 个图像 Token）。 - 其他`qwen-vl-plus`模型、其他`qwen-vl-max`模型、`Qwen2.5-VL`开源系列及`QVQ`系列模型：默认值和最小值均为`51380224`，该值对应 `65536` 个图像 Token（每 28×28 像素对应 1 个图像 Token）。 **示例值** - 输入视频文件时：`{"type": "video_url","video_url": {"url":"https://xxxx.mp4"},"total_pixels": 134217728}` - 输入图像列表时：`{"type": "video","video": ["https://xx1.jpg",...,"https://xxn.jpg"],"total_pixels": 134217728}` **cache\\_control** `*object*` （可选） 用于开启显式缓存。相关文档：[显式缓存](https://help.aliyun.com/zh/model-studio/context-cache#825f201c5fy6o)。 **属性** **type** `*string*`**（必选）** 仅支持设定为`ephemeral`。 **role** `*string*` **（必选）** 用户消息的角色，固定为`user`。 Assistant Message `*object*` （可选） 模型的回复。通常用于在多轮对话中作为上下文回传给模型。 **属性** **content** `*string*` （可选） 模型回复的文本内容。包含`tool_calls`时，`content`可以为空；否则`content`为必选。 **role** `*string*` **（必选）** 助手消息的角色，固定为`assistant`。 **partial** `*boolean*` （可选）默认值为`false` 是否开启前缀续写。 可选值： - true：开启； - false：不开启。 支持的模型参见[前缀续写](https://help.aliyun.com/zh/model-studio/partial-mode)。 **tool\\_calls** `*array*` （可选） 发起 Function Calling 后，返回的工具与入参信息，包含一个或多个对象。由上一轮模型响应的`tool_calls`字段获得。 **属性** **id** `*string*` **（必选）** 工具响应的ID。 **type** `*string*`**（必选）** 工具类型，当前只支持设为`function`。 **function** `*object*`**（必选）** 工具与入参信息。 **属性** **name** `*string*`**（必选）** 工具名称。 **arguments** `*string*`**（必选）** 入参信息，为JSON格式字符串。 **index** `*integer*`**（必选）** 当前工具信息在`tool_calls`数组中的索引。 Tool Message `*object*` （可选） 工具的输出信息。 **属性** **content** `*string*` **（必选）** 工具函数的输出内容，必须为字符串。若工具返回结构化数据（如JSON），需将其序列化为字符串。 **role** `*string*` **（必选）** 固定为`tool`。 **tool\\_call\\_id** `*string*` **（必选）** 发起 Function Calling 后返回的 id，通过completion.choices\\[0\\].message.tool\\_calls\\[$index\\].id获取，用于标记 Tool Message 对应的工具。 |
| **stream** `*boolean*` （可选） 默认值为 `false` 是否以流式输出方式回复。相关文档：[流式输出](https://help.aliyun.com/zh/model-studio/stream) 可选值： - `false`：模型生成全部内容后一次性返回； - `true`：边生成边输出，每生成一部分内容即返回一个数据块（chunk）。需实时逐个读取这些块以拼接完整回复。 推荐设置为`true`，可提升阅读体验并降低超时风险。 **说明** 非流式调用若超过 300 秒未完成，服务将中断请求并返回已生成的内容（而非报错）。建议输出较长的场景务必使用流式调用。详情请参见[文本生成模型概述](https://help.aliyun.com/zh/model-studio/text-generation#fb9055c4ceibm)中的超时说明。 |
| **stream\\_options** `*object*` （可选） 流式输出的配置项，仅在 `stream` 为 `true` 时生效。 **属性** **include\\_usage** `*boolean*` （可选）默认值为`false` 是否在响应的**最后一个数据块**包含Token消耗信息。 可选值： - `true`：包含； - `false`：不包含。 > 流式输出时，Token 消耗信息仅可出现在响应的最后一个数据块。 |
| **modalities** `array` （可选）默认值为`["text"]` 输出数据的模态，仅适用于 Qwen-Omni 模型。相关文档：[非实时（Qwen-Omni）](https://help.aliyun.com/zh/model-studio/qwen-omni) 可选值： - `["text","audio"]`：输出文本与音频； - `["text"]`：仅输出文本。 |
| **audio** `*object*` （可选） 输出音频的音色与格式，仅适用于 Qwen-Omni 模型，且`modalities`参数需为`["text","audio"]`。相关文档：[非实时（Qwen-Omni）](https://help.aliyun.com/zh/model-studio/qwen-omni) **属性** **voice** `*string*` **（必选）** 输出音频的音色。请参见[非实时（Qwen-Omni）](https://help.aliyun.com/zh/model-studio/qwen-omni#a447c4fbf5ul0)。 **format** `*string*` **（必选）** 输出音频的格式，仅支持设定为`wav`。 |
| **temperature** `*float*` （可选） 采样温度，控制模型生成文本的多样性。 temperature越高，生成的文本更多样，反之，生成的文本更确定。 取值范围： \\[0, 2) temperature与top\\_p均可以控制生成文本的多样性，建议只设置其中一个值。更多说明，请参见[概述](https://help.aliyun.com/zh/model-studio/text-generation#ad7b336bec5fw)。 **temperature默认值** - Qwen3.6（非思考模式）、Qwen3.5-Omni、Qwen3.5（非思考模式）、Qwen3（非思考模式）、Qwen3-Instruct系列、Qwen3-Coder系列、qwen-max系列、qwen-plus系列（非思考模式）、qwen-flash系列（非思考模式）、qwen-turbo系列（非思考模式）、qwen开源系列、qwen-coder系列、qwen-doc-turbo、qwen-vl-max-2025-08-13、Qwen3-VL（非思考）：0.7； - QVQ系列 、qwen-vl-plus-2025-07-10、qwen-vl-plus-2025-08-15 : 0.5； - qwen-audio-turbo系列：0.00001； - qwen-vl系列、qwen2.5-omni-7b、qvq-72b-preview：0.01； - qwen-math系列：0； - Qwen3.6（思考模式）、Qwen3.5（思考模式）、Qwen3（思考模式）、Qwen3-Thinking、Qwen3-Omni-Captioner、QwQ 系列：0.6； - qwen3-max-preview（思考模式）、qwen-long系列： 1.0； - qwen-plus-character：0.92 - qwen3-omni-flash系列：0.9 - Qwen3-VL（思考模式）：0.8 > 不建议修改QVQ模型的默认temperature值 。 |
| **top\\_p** `*float*` （可选） 核采样的概率阈值，控制模型生成文本的多样性。 top\\_p越高，生成的文本更多样。反之，生成的文本更确定。 取值范围：（0,1.0\\] temperature与top\\_p均可以控制生成文本的多样性，建议只设置其中一个值。更多说明，请参见[概述](https://help.aliyun.com/zh/model-studio/text-generation#ad7b336bec5fw)。 **top\\_p默认值** Qwen3.6（非思考模式）、Qwen3.5-Omni、Qwen3.5（非思考模式）、Qwen3（非思考模式）、Qwen3-Instruct系列、Qwen3-Coder系列、qwen-max系列、qwen-plus系列（非思考模式）、qwen-flash系列（非思考模式）、qwen-turbo系列（非思考模式）、Qwen 2.5开源系列、qwen-coder系列、qwen-long、qwq-32b-preview、qwen-doc-turbo、qwen-vl-max-2025-08-13、Qwen3-VL（非思考）：0.8； qwen-vl-max-2024-11-19、qwen-omni-turbo 系列：0.01； qwen-vl-plus系列、qwen-vl-max、qwen-vl-max-latest、qwen-vl-max-2025-04-08、qwen-vl-max-2025-04-02、qwen-vl-max-2025-01-25、qwen-vl-max-2024-12-30、qvq-72b-preview、qwen2.5-vl-3b-instruct、qwen2.5-vl-7b-instruct、qwen2.5-vl-32b-instruct、qwen2.5-vl-72b-instruct、qwen2.5-omni-7b：0.001； QVQ系列、qwen-vl-plus-2025-07-10、qwen-vl-plus-2025-08-15 : 0.5； qwen3-max-preview（思考模式）、qwen-math系列、Qwen3-Omni-Flash系列：1.0； Qwen3.6（思考模式）、Qwen3.5（思考模式）、Qwen3（思考模式）、Qwen3-VL（思考模式）、Qwen3-Thinking、QwQ 系列、Qwen3-Omni-Captioner、qwen-plus-character：0.95 > 不建议修改QVQ模型的默认 top\\_p 值。 |
| **top\\_k** `*integer*` （可选） 指定生成过程中用于采样的候选 Token 数量。值越大，输出越随机；值越小，输出越确定。若设为 `null` 或大于 100，则禁用 `top_k` 策略，仅 `top_p` 策略生效。取值必须为大于或等于 0 的整数。 **top\\_k默认值** QVQ系列、qwen-vl-plus-2025-07-10、qwen-vl-plus-2025-08-15：10； QwQ 系列：40； qwen-math 系列、其余qwen-vl-plus系列、qwen-vl-max-2025-08-13之前的模型、qwen-audio-turbo系列、qwen2.5-omni-7b、qvq-72b-preview：1； Qwen3-Omni-Flash系列：50； 其余模型均为20。 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：extra\\_body={"top\\_k":xxx}。 > 不建议修改QVQ模型的默认 top\\_k 值。 |
| **repetition\\_penalty** `*float*` （可选） 模型生成时连续序列中的重复度。提高repetition\\_penalty时可以降低模型生成的重复度，1.0表示不做惩罚。没有严格的取值范围，只要大于0即可。 **repetition\\_penalty默认值** - qwen-max、qwen-max-latest、qwen-max-2024-09-19、qwen-math系列、qwen-vl-max系列、qvq-72b-preview、qwen-vl-plus-2025-01-02、qwen-vl-plus-2025-05-07、qwen-vl-plus-2025-07-10、qwen-vl-plus-2025-08-15、qwen-vl-plus-latest、qwen2.5-vl-3b-instruct、qwen2.5-vl-7b-instruct、qwen2.5-vl-32b-instruct、qwen2.5-vl-72b-instruct、qwen-audio-turbo系列、QVQ系列、QwQ系列、qwq-32b-preview、Qwen3-VL： 1.0； - qwen-coder系列、qwen2.5-1.5b-instruct、qwen2.5-0.5b-instruct、qwen2-1.5b-instruct、qwen2-0.5b-instruct、qwen2.5-omni-7b：1.1； - qwen-vl-plus、qwen-vl-plus-2025-01-25：1.2； - 其余模型为1.05。 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：extra\\_body={"repetition\\_penalty":xxx}。 > 使用qwen-vl-plus\\_2025-01-25模型进行文字提取时，建议设置repetition\\_penalty为1.0。 > 不建议修改QVQ模型的默认 repetition\\_penalty 值。 |
| **presence\\_penalty** `*float*` （可选） 控制模型生成文本时的内容重复度。 取值范围：\\[-2.0, 2.0\\]。正值降低重复度，负值增加重复度。 在创意写作或头脑风暴等需要多样性、趣味性或创造力的场景中，建议调高该值；在技术文档或正式文本等强调一致性与术语准确性的场景中，建议调低该值。 **presence\\_penalty默认值** Qwen3.6（非思考模式）、Qwen3.5-Omni、Qwen3.5（非思考模式）、qwen3-max-preview（思考模式）、Qwen3（非思考模式）、Qwen3-Instruct系列、qwen3-0.6b/1.7b/4b（思考模式）、QVQ系列、qwen-max、qwen-max-latest、qwen-max-latest、qwen-max-2024-09-19、qwen2.5-vl系列、qwen-vl-max系列、qwen-vl-plus、qqwen-vl-plus-2025-01-02、Qwen3-VL（非思考）：1.5； qwen-vl-plus-latest、qwen-vl-plus-2025-08-15、qwen-vl-plus-2025-07-10：1.2 qwen-vl-plus-2025-01-25：1.0； qwen3-8b/14b/32b/30b-a3b/235b-a22b（思考模式）、qwen-plus/qwen-plus-latest/2025-04-28（思考模式）、qwen-turbo/qwen-turbo/2025-04-28（思考模式）：0.5； 其余均为0.0。 **原理介绍** 如果参数值是正数，模型将对目前文本中已存在的Token施加一个惩罚值（惩罚值与文本出现的次数无关），减少这些Token重复出现的几率，从而减少内容重复度，增加用词多样性。 **示例** 提示词：把这句话翻译成中文“This movie is good. The plot is good, the acting is good, the music is good, and overall, the whole movie is just good. It is really good, in fact. The plot is so good, and the acting is so good, and the music is so good.” 参数值为2.0：这部电影很好。剧情很棒，演技棒，音乐也非常好听，总的来说，整部电影都好得不得了。实际上它真的很优秀。剧情非常精彩，演技出色，音乐也是那么的动听。 参数值为0.0：这部电影很好。剧情好，演技好，音乐也好，总的来说，整部电影都很好。事实上，它真的很棒。剧情非常好，演技也非常出色，音乐也同样优秀。 参数值为-2.0：这部电影很好。情节很好，演技很好，音乐也很好，总的来说，整部电影都很好。实际上，它真的很棒。情节非常好，演技也非常好，音乐也非常好。 > 使用qwen-vl-plus-2025-01-25模型进行文字提取时，建议设置presence\\_penalty为1.5。 > 不建议修改QVQ模型的默认presence\\_penalty值。 |
| **response\\_format** `*object*` （可选） 默认值为`{"type": "text"}` 返回内容的格式。可选值： - `{"type": "text"}`：输出文字回复； - `{"type": "json_object"}`：输出标准格式的JSON字符串。 > 相关文档：[结构化输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)。 > 若指定为`{"type": "json_object"}`，需在提示词中明确指示模型输出JSON，如：“请按照json格式输出”，否则会报错。 > 支持的模型参见[结构化输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)。 **属性** **type** `*string*` **（必选）** 返回内容的格式。可选值： - `text`：输出文字回复； - `json_object`：输出标准格式的JSON字符串； |
| **max\\_tokens** `*integer*` （可选） 用于限制模型输出的最大 Token 数。若生成内容超过此值，生成将提前停止，且返回的`finish_reason`为`length`。 默认值与最大值均为模型的最大输出长度，请参见百炼控制台。 适用于需控制输出长度的场景，如生成摘要、关键词，或用于降低成本、缩短响应时间。 触发 `max_tokens` 时，响应的 finish\\_reason 字段为 `length`。 > `max_tokens`不限制思考模型思维链的长度。 |
| **vl\\_high\\_resolution\\_images** `*boolean*` （可选）默认值为`false` 是否将输入图像的像素上限提升至 16384 Token 对应的像素值。相关文档：[处理高分辨率图像](https://help.aliyun.com/zh/model-studio/vision#e7e2db755f9h7)。 - `vl_high_resolution_images：true`，使用固定分辨率策略，忽略 `max_pixels` 设置，超过此分辨率时会将图像总像素缩小至此上限内。 **点击查看各模型像素上限** `vl_high_resolution_images`为`True`时，不同模型像素上限不同： - `Qwen3.6`系列、`Qwen3.5`系列、`Qwen3-VL系列`、`qwen-vl-max`、`qwen-vl-max-latest`、`qwen-vl-max-0813`、`qwen-vl-plus`、`qwen-vl-plus-latest`、`qwen-vl-plus-0815``、qwen-vl-plus-0710`模型：`16777216`（每`Token`对应`32*32`像素，即`16384*32*32`） - `QVQ系列`、其他`Qwen2.5-VL系列`模型：`12845056`（每`Token`对应`28*28`像素，即 `16384*28*28`） - `vl_high_resolution_images`为`false`，像素上限由 `max_pixels` 决定，输入图像的像素超过`max_pixels`会将图像缩小至`max_pixels`内。各模型的默认像素上限即`max_pixels`的默认值。 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：extra\\_body={"vl\\_high\\_resolution\\_images":xxx}。 |
| **n** `*integer*` （可选） 默认值为1 生成响应的数量，取值范围是`1-4`。适用于需生成多个候选响应的场景，例如创意写作或广告文案。 > 仅支持 [Qwen3（非思考模式）](https://help.aliyun.com/zh/model-studio/deep-thinking#be9890136awsc)、qwen-plus-character 模型。 > 若传入 `tools` 参数， 请将`n` 设为 1。 > 增大 n 会增加输出 Token 的消耗，但不增加输入 Token 消耗。 |
| **enable\\_thinking** `*boolean*` （可选） 使用混合思考（回复前既可思考也可不思考）模型时，是否开启思考模式。适用于 Qwen3.6、Qwen3.5、Qwen3 、Qwen3-Omni-Flash、Qwen3-VL模型。相关文档：[深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking) 可选值： - `true`：开启 > 开启后，思考内容将通过`reasoning_content`字段返回。 - `false`：不开启 不同模型的默认值：[支持的模型](https://help.aliyun.com/zh/model-studio/deep-thinking#78286fdc35hlw) > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：`extra_body={"enable_thinking": xxx}`。 |
| **preserve\\_thinking** `*boolean*` （可选）默认值为 `false` 是否将对话历史中 assistant 消息的 reasoning\\_content 拼接至模型输入。适用于需要模型参考历史思考过程的场景。 目前支持qwen3.6-max-preview、qwen3.6-plus、qwen3.6-plus-2026-04-02、kimi-k2.6（阿里云百炼部署）。 - 若历史消息中不包含 reasoning\\_content，开启此参数不会报错，正常兼容。 - 开启后，历史对话中的 reasoning\\_content 会计入输入 Token 数量并计费。 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：`extra_body={"preserve_thinking": True}`。 |
| **thinking\\_budget** `*integer*` （可选） 思考过程的最大 Token 数。适用于Qwen3.6、Qwen3.5、Qwen3-VL、Qwen3 的商业版与开源版模型。相关文档：[限制思考长度](https://help.aliyun.com/zh/model-studio/deep-thinking#e7c0002fe4meu)。 默认值为模型最大思维链长度，请参见：模型列表 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：`extra_body={"thinking_budget": xxx}`。 |
| **enable\\_code\\_interpreter** `*boolean*` （可选）默认值为 `false` 是否开启代码解释器功能。相关文档：[代码解释器](https://help.aliyun.com/zh/model-studio/qwen-code-interpreter) 可选值： - `true`：开启 - `false`：不开启 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：`extra_body={"enable_code_interpreter": xxx}`。 |
| **seed** `*integer*` （可选） 随机数种子。用于确保在相同输入和参数下生成结果可复现。若调用时传入相同的 `seed` 且其他参数不变，模型将尽可能返回相同结果。 取值范围：`[0,231−1]`。 **seed默认值** qwen-vl-plus-2025-01-02、qwen-vl-max、qwen-vl-max-latest、qwen-vl-max-2025-04-08、qwen-vl-max-2025-04-02、qwen-vl-max-2024-12-30、qvq-72b-preview、qvq-max系列：3407； qwen-vl-max-2025-01-25、qwen-vl-max-2024-11-19、qwen-vl-max-2024-02-01、qwen-vl-plus、qwen-vl-plus-latest、qwen-vl-plus-2025-05-07、qwen-vl-plus-2025-01-25：无默认值； 其余模型均为1234。 |
| **logprobs** `*boolean*` （可选）默认值为 `false` 是否返回输出 Token 的对数概率，可选值： - `true` 返回 - `false` 不返回 > 思考阶段生成的内容（`reasoning_content`）不会返回对数概率。 **支持的模型** - qwen-plus系列的快照模型（不包含稳定版模型） - qwen-turbo 系列的快照模型（不包含稳定版模型） - qwen3-vl-plus系列模型（包含稳定版模型） - qwen3-vl-flash系列模型（包含稳定版模型） - Qwen3 开源模型 |
| **top\\_logprobs** `*integer*` （可选）默认值为0 指定在每一步生成时，返回模型最大概率的候选 Token 个数。 取值范围：\\[0,5\\] 仅当 `logprobs` 为 `true` 时生效。 |
| **stop** `*string 或 array*` （可选） 用于指定停止词。当模型生成的文本中出现`stop` 指定的字符串或`token_id`时，生成将立即终止。 可传入敏感词以控制模型的输出。 > stop为数组时，不可将`token_id`和字符串同时作为元素输入，比如不可以指定为`["你好",104307]`。 |
| **tools** `*array*` （可选） 包含一个或多个工具对象的数组，供模型在 Function Calling 中调用。相关文档：[Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling) 设置 tools 且模型判断需要调用工具时，响应会通过 tool\\_calls 返回工具信息。 **属性** **type** `*string*` **（必选）** 工具类型，当前仅支持设为`function`。 **function** `*object*` **（必选）** **属性** **name** `*string*` **（必选）** 工具名称。仅允许字母、数字、下划线（`_`）和短划线（`-`），最长 64 个 Token。 **description** `*string*` **（必选）** 工具描述信息，帮助模型判断何时以及如何调用该工具。 **parameters** `*object*` （可选）默认值为 `{}` 工具的参数描述，需要是一个合法的JSON Schema。JSON Schema的描述可以见[链接](https://json-schema.org/understanding-json-schema)。若`parameters`参数为空，表示该工具没有入参（如时间查询工具）。 > 为提高工具调用的准确性，建议传入 `parameters`。 |
| **tool\\_choice** `*string 或 object*` （可选）默认值为 `auto` 工具选择策略。若需对某类问题强制指定工具调用方式（例如始终使用某工具或禁用所有工具），可设置此参数。 可选值： - `auto` 大模型自主选择工具策略。 - `none` 若不希望进行工具调用，可设定`tool_choice`参数为`none`； - `{"type": "function", "function": {"name": "the_function_to_call"}}` 若希望强制调用某个工具，可设定`tool_choice`参数为`{"type": "function", "function": {"name": "the_function_to_call"}}`，其中`the_function_to_call`是指定的工具函数名称。 > 思考模式的模型不支持强制调用某个工具。 |
| **parallel\\_tool\\_calls** `*boolean*` （可选）默认值为 `false` 是否开启并行工具调用。相关文档：[并行工具调用](https://help.aliyun.com/zh/model-studio/qwen-function-calling#cb6b5c484bt4x) 可选值： - `true`：开启 - `false`：不开启 |
| **enable\\_search** `*boolean*` （可选）默认值为 `false` 是否开启联网搜索。相关文档：[联网搜索](https://help.aliyun.com/zh/model-studio/web-search) 可选值： - `true`：开启； > 若开启后未联网搜索，可优化提示词，或设置`search_options`中的`forced_search`参数开启强制搜索。 - `false`：不开启。 > 启用互联网搜索功能可能会增加 Token 的消耗。 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：`extra_body={"enable_search": True}`。 |     |
| **search\\_options** `*object*` （可选） 联网搜索的策略。相关文档：[联网搜索](https://help.aliyun.com/zh/model-studio/web-search) **属性** **forced\\_search** `*boolean*`（可选）默认值为`false` 是否强制开启联网搜索，仅当`enable_search`为`true`时生效。 可选值： - true：强制开启； - false：不强制开启，由模型判断是否联网搜索。 **search\\_strategy** `*string*`（可选）默认值为`turbo` 搜索量级策略，仅当`enable_search`为`true`时生效。 可选值： - `turbo` （默认）: 兼顾响应速度与搜索效果，适用于大多数场景。 - `max`: 采用更全面的搜索策略，可调用多源搜索引擎，以获取更详尽的搜索结果，但响应时间可能更长。 - `agent`：可多次调用联网搜索工具与大模型，实现多轮信息检索与内容整合。 > 该策略仅适用于 qwen3.5-plus、qwen3.5-plus-2026-02-15、qwen3.5-flash、qwen3.5-flash-2026-02-23、qwen3-max、qwen3-max-2026-01-23、qwen3-max-2025-09-23、qwen3.5-omni-plus、qwen3.5-omni-plus-2026-03-15、qwen3.5-omni-flash、qwen3.5-omni-flash-2026-03-15。 - `agent_max`：在`agent`策略基础上支持网页抓取，参见：[网页抓取](https://help.aliyun.com/zh/model-studio/web-extractor)。 > 该策略仅适用于qwen3-max、qwen3-max-2026-01-23的思考模式。 **enable\\_search\\_extension** `*boolean*`（可选）默认值为`false` 是否开启垂域搜索，仅当`enable_search`为`true`时生效。 可选值： - `true`：开启。 - `false`：不开启。 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：`extra_body={"search_options": xxx}`。 |     |
| **X-DashScope-DataInspection** `*string*` （可选） 在千问 API 的内容安全能力基础上，是否进一步识别输入输出内容的违规信息。取值如下： - `'{"input":"cip","output":"cip"}'`：进一步识别； - 不设置该参数：不进一步识别。 通过 HTTP 调用时请放入请求头：`-H "X-DashScope-DataInspection: {\\"input\\": \\"cip\\", \\"output\\": \\"cip\\"}"`； 通过 Python SDK 调用时请通过`extra_headers`配置：`extra_headers={'X-DashScope-DataInspection': '{"input":"cip","output":"cip"}'}`。 详细使用方法请参见[输⼊输出 AI 安全护栏](https://help.aliyun.com/zh/model-studio/content-security)。 > 不支持通过 Node.js SDK设置。 |     |
| **skill** `*array*` （可选） 技能参数，用于启用特定生成技能（如PPT生成）。仅`qwen-doc-turbo`模型支持。详细用法请参见[生成PPT](https://help.aliyun.com/zh/model-studio/data-mining-qwen-doc#a1b2c3d4e5ppt)。 > 该参数非OpenAI标准参数。通过 Python SDK调用时，请放入 **extra\\_body** 对象中。配置方式为：`extra_body={"skill": [...]}`。 > 使用 **skill 时，stream** 必须设置为 **true**。 **属性** **type** `*string*`**（必选）** 技能类型。当前支持： - `ppt`：PPT生成。 **mode** `*string*` （可选） PPT生成模式。可选值： - `general` （默认值）：模板模式，需配合`template_id` 使用，生成HTML格式的PPT。 - `creative` ：创意模式，无需模板，生成图版PPT（每页为图片）。 **template\\_id** `*string*`（可选） PPT模板ID。与`mode`为`general`或未设置`mode`时配合使用。可选值： - `news_01`：新闻模板 - `summary_01`：总结模板 - `internet_01`：互联网模板 - `thesis_01`：论文模板 |     |

| ## **chat响应对象（非流式输出）** | ``` { "choices": [ { "message": { "role": "assistant", "content": "我是阿里云开发的一款超大规模语言模型，我叫千问。" }, "finish_reason": "stop", "index": 0, "logprobs": null } ], "object": "chat.completion", "usage": { "prompt_tokens": 3019, "completion_tokens": 104, "total_tokens": 3123, "prompt_tokens_details": { "cached_tokens": 2048 } }, "created": 1735120033, "system_fingerprint": null, "model": "qwen-plus", "id": "chatcmpl-6ada9ed2-7f33-9de2-8bb0-78bd4035025a" } ``` |
| --- | --- |
| **id** `*string*` 本次调用的唯一标识符。 |
| **choices** `*array*` 模型生成内容的数组。 **属性** **finish\\_reason** `*string*` 模型停止生成的原因。 有三种情况： - 触发输入参数中的`stop`参数，或自然停止输出时为`stop`； - 生成长度过长而结束为`length`； - 需要调用工具而结束为`tool_calls`。 **index** `*integer*` 当前对象在`choices`数组中的索引。 **logprobs** `*object*` 模型输出的 Token 概率信息。 **属性** **content** `*array*` 包含每个 Token 及其对数概率的数组。 **属性** **token** `*string*` 当前 Token 的文本。 **bytes** `*array*` 当前 Token 的 UTF‑8 原始字节列表，用于精确还原输出内容（例如表情符号或中文字符）。 **logprob** `*float*` 当前 Token 的对数概率。返回值为 `null` 表示概率值极低。 **top\\_logprobs** `*array*` 当前 Token 位置最可能的若干候选 Token，数量与请求参数`top_logprobs`保持一致。每个元素包含： **属性** **token** `*string*` 候选 Token 文本。 **bytes** `*array*` 当前 Token 的 UTF‑8 原始字节列表，用于精确还原输出内容（例如表情符号或中文字符）。 **logprob** `*float*` 该候选 Token 的对数概率。返回值为 null 表示概率值极低。 **message** `*object*` 模型输出的消息。 **属性** **content** `*string*` 模型的回复内容。 **reasoning\\_content** `*string*` 模型的思维链内容。 **refusal** `*string*` 该参数当前固定为`null`。 **role** `*string*` 消息的角色，固定为`assistant`。 **audio** `*object*` 该参数当前固定为`null`。 **function\\_call**（即将废弃）`*object*` 该值固定为`null`，请参考`tool_calls`参数。 **tool\\_calls** `*array*` 在发起 Function Calling后，模型生成的工具与入参信息。 **属性** **id** `*string*` 本次工具响应的唯一标识符。 **type** `*string*` 工具类型，当前只支持`function`。 **function** `*object*` 工具信息。 **属性** **name** `*string*` 工具名称。 **arguments** `*string*` 入参信息，为JSON格式字符串。 > 由于大模型响应有一定随机性，输出的入参信息可能不符合函数签名。请在调用前校验参数有效性 **index** `*integer*` 当前工具在`tool_calls`数组中的索引。 |
| **created** `*integer*` 请求创建时的 Unix 时间戳（秒）。 |
| **model** `*string*` 本次请求使用的模型。 |
| **object** `*string*` 始终为`chat.completion`。 |
| **service\\_tier** `*string*` 该参数当前固定为`null`。 |
| **system\\_fingerprint** `*string*` 该参数当前固定为`null`。 |
| **usage** `*object*` 本次请求的 Token 消耗信息。 **属性** **completion\\_tokens** `*integer*` 模型输出的 Token 数。 **prompt\\_tokens** `*integer*` 输入的 Token 数。 **total\\_tokens** `*integer*` 消耗的总 Token 数，为`prompt_tokens`与`completion_tokens`的总和。 **completion\\_tokens\\_details** `*object*` 使用[Qwen-VL 模型](https://help.aliyun.com/zh/model-studio/vision)时输出Token的细粒度分类。 **属性** **audio\\_tokens** `*integer*` 该参数当前固定为`null`。 **reasoning\\_tokens** `*integer*` 该参数当前固定为`null`。 **text\\_tokens** `*integer*` [Qwen-VL 模型](https://help.aliyun.com/zh/model-studio/vision)输出文本的Token数。 **prompt\\_tokens\\_details** `*object*` 输入 Token 的细粒度分类。 **属性** **audio\\_tokens** `*integer*` 该参数当前固定为`null`。 **cached\\_tokens** `*integer*` 命中 Cache 的 Token 数。Context Cache 详情请参见[上下文缓存](https://help.aliyun.com/zh/model-studio/context-cache)。 **text\\_tokens** `*integer*` [Qwen-VL 模型](https://help.aliyun.com/zh/model-studio/vision)输入的文本 Token 数。 **image\\_tokens** `*integer*` [Qwen-VL 模型](https://help.aliyun.com/zh/model-studio/vision)输入的图像 Token数。 **video\\_tokens** `*integer*` [Qwen-VL 模型](https://help.aliyun.com/zh/model-studio/vision)输入的视频文件或者图像列表 Token 数。 **cache\\_creation** `*object*` [显式缓存](https://help.aliyun.com/zh/model-studio/context-cache#825f201c5fy6o)创建信息。 **属性** **ephemeral\\_5m\\_input\\_tokens** `*integer*` 创建显式缓存的 Token 数。 **cache\\_creation\\_input\\_tokens** `*integer*` 创建显式缓存的 Token 数。 **cache\\_type** `*string*` 使用[显式缓存](https://help.aliyun.com/zh/model-studio/context-cache#825f201c5fy6o)时，参数值为`ephemeral`，否则该参数不存在。 |

| ## **chat响应chunk对象（流式输出）** | ``` {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"","function_call":null,"refusal":null,"role":"assistant","tool_calls":null},"finish_reason":null,"index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"我是","function_call":null,"refusal":null,"role":null,"tool_calls":null},"finish_reason":null,"index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"来自","function_call":null,"refusal":null,"role":null,"tool_calls":null},"finish_reason":null,"index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"阿里","function_call":null,"refusal":null,"role":null,"tool_calls":null},"finish_reason":null,"index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"云的超大规模","function_call":null,"refusal":null,"role":null,"tool_calls":null},"finish_reason":null,"index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"语言模型，我","function_call":null,"refusal":null,"role":null,"tool_calls":null},"finish_reason":null,"index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"叫千问千","function_call":null,"refusal":null,"role":null,"tool_calls":null},"finish_reason":null,"index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"问。","function_call":null,"refusal":null,"role":null,"tool_calls":null},"finish_reason":null,"index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[{"delta":{"content":"","function_call":null,"refusal":null,"role":null,"tool_calls":null},"finish_reason":"stop","index":0,"logprobs":null}],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":null} {"id":"chatcmpl-e30f5ae7-3063-93c4-90fe-beb5f900bd57","choices":[],"created":1735113344,"model":"qwen-plus","object":"chat.completion.chunk","service_tier":null,"system_fingerprint":null,"usage":{"completion_tokens":17,"prompt_tokens":22,"total_tokens":39,"completion_tokens_details":null,"prompt_tokens_details":{"audio_tokens":null,"cached_tokens":0}}} ``` |
| --- | --- |
| **id** `*string*` 本次调用的唯一标识符。每个chunk对象有相同的 id。 |
| **choices** `*array*` 模型生成内容的数组，可包含一个或多个对象。若设置`include_usage`参数为`true`，则`choices`在最后一个chunk中为空数组。 **属性** **delta** `*object*` 请求的增量对象。 **属性** **content** `*string*` 增量消息内容。 **reasoning\\_content** `*string*` 增量思维链内容。 **function\\_call** `*object*` 该值默认为`null`，请参考`tool_calls`参数。 **audio** `*object*` 使用 [Qwen-Omni](https://help.aliyun.com/zh/model-studio/qwen-omni) 模型时生成的回复。 **属性** **data** `*string*` 增量的 Base64 音频编码数据。 **expires\\_at** `*integer*` 创建请求时的时间戳。 **refusal** `*object*` 该参数当前固定为`null`。 **role** `*string*` 增量消息对象的角色，只在第一个chunk中有值。 **tool\\_calls** `*array*` 在发起 Function Calling后，模型生成的工具与入参信息。 **属性** **index** `*integer*` 当前工具在`tool_calls`数组中的索引。 **id** `*string*` 本次工具响应的唯一标识符。 **function** `*object*` 被调用的工具信息。 **属性** **arguments** `*string*` 增量的入参信息，所有chunk的`arguments`拼接后为完整的入参。 > 由于大模型响应有一定随机性，输出的入参信息可能不符合函数签名。请在调用前校验参数有效性。 **name** `*string*` 工具名称，只在第一个chunk中有值。 **type** `*string*` 工具类型，当前只支持`function`。 **finish\\_reason** `*string*` 模型停止生成的原因。有四种情况： - 因触发输入参数中的`stop`参数，或自然停止输出时为`stop`； - 生成未结束时为`null`； - 生成长度过长而结束为`length`； - 需要调用工具而结束为`tool_calls`。 **index** `*integer*` 当前响应在`choices`数组中的索引。当输入参数 n 大于1时，需根据本参数进行不同响应对应的完整内容的拼接。 **logprobs** `*object*` 当前对象的概率信息。 **属性** **content** `*array*` 带有对数概率信息的 Token 数组。 **属性** **token** `*string*` 当前 Token。 **bytes** `*array*` 当前 Token 的 UTF‑8 原始字节列表，用于精确还原输出内容，在处理表情符号、中文字符时有帮助。 **logprob** `*float*` 当前 Token 的对数概率。返回值为 null 表示概率值极低。 **top\\_logprobs** `*array*` 当前 Token 位置最可能的若干个 Token 及其对数概率，元素个数与入参的`top_logprobs`保持一致。 **属性** **token** `*string*` 当前 Token。 **bytes** `*array*` 当前 Token 的 UTF‑8 原始字节列表，用于精确还原输出内容，在处理表情符号、中文字符时有帮助。 **logprob** `*float*` 当前 Token 的对数概率。返回值为 null 表示概率值极低。 |
| **created** `*integer*` 本次请求被创建时的时间戳。每个chunk有相同的时间戳。 |
| **model** `*string*` 本次请求使用的模型。 |
| **object** `*string*` 始终为`chat.completion.chunk`。 |
| **service\\_tier** `*string*` 该参数当前固定为`null`。 |
| **system\\_fingerprint**`*string*` 该参数当前固定为`null`。 |
| **usage** `*object*` 本次请求消耗的Token。只在`include_usage`为`true`时，在最后一个chunk显示。 **属性** **completion\\_tokens** `*integer*` 模型输出的 Token 数。 **prompt\\_tokens** `*integer*` 输入 Token 数。 **total\\_tokens** `*integer*` 总 Token 数，为`prompt_tokens`与`completion_tokens`的总和。 **completion\\_tokens\\_details** `*object*` 输出 Token 的详细信息。 **属性** **audio\\_tokens** `*integer*` [Qwen-Omni 模型](https://help.aliyun.com/zh/model-studio/qwen-omni)输出的音频 Token 数。 **reasoning\\_tokens** `*integer*` 思考过程 Token 数。 **text\\_tokens** `*integer*` 输出文本 Token 数。 **prompt\\_tokens\\_details** `*object*` 输入 Token的细粒度分类。 **属性** **audio\\_tokens** `*integer*` 输入音频的 Token 数。 > 视频文件中的音频 Token 数通过本参数返回。 **text\\_tokens** `*integer*` 输入文本的 Token 数。 **video\\_tokens** `*integer*` 输入视频（图片列表形式或视频文件）的 Token 数。 **image\\_tokens** `*integer*` 输入图片的 Token 数。 **cached\\_tokens** `*integer*` 命中缓存的 Token 数。Context Cache 详情请参见[上下文缓存](https://help.aliyun.com/zh/model-studio/context-cache)。 **cache\\_creation** `*object*` [显式缓存](https://help.aliyun.com/zh/model-studio/context-cache#825f201c5fy6o)创建信息。 **属性** **ephemeral\\_5m\\_input\\_tokens** `*integer*` 创建显式缓存的 Token 数。 **cache\\_creation\\_input\\_tokens** `*integer*` 创建显式缓存的 Token 数。 **cache\\_type** `*string*` 缓存类型，固定为`ephemeral`。 |

.aliyun-docs-content .one-codeblocks pre { max-height: calc(80vh - 136px) !important; height: auto; } .tab-item { font-size: 12px !important; /\* 你可以根据需要调整字体大小 \*/ padding: 0px 5px !important; } .expandable-content { border-left: none !important; border-right: none !important; border-bottom: none !important; } .one-codeblocks.stick-top.section { overflow: hidden !important; }

.table-wrapper { overflow: visible !important; } /\* 调整 table 宽度 \*/ .aliyun-docs-content table.medium-width { max-width: 1018px; width: 100%; } .aliyun-docs-content table.table-no-border tr td:first-child { padding-left: 0; } .aliyun-docs-content table.table-no-border tr td:last-child { padding-right: 0; } /\* 支持吸顶 \*/ div:has(.aliyun-docs-content), .aliyun-docs-content .markdown-body { overflow: visible; } .stick-top { position: sticky; top: 46px; } /\*\*代码块字体\*\*/ /\* 减少表格中的代码块 margin，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body table .help-code-block { margin: 0 !important; } /\* 减少表格中的代码块字号，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body .help-code-block pre { font-size: 12px !important; } /\* 减少表格中的代码块字号，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body .help-code-block pre code { font-size: 12px !important; } /\*\* API Reference 表格 \*\*/ .aliyun-docs-content table.api-reference tr td:first-child { margin: 0px; border-bottom: 1px solid #d8d8d8; } .aliyun-docs-content table.api-reference tr:last-child td:first-child { border-bottom: none; } .aliyun-docs-content table.api-reference p { color: #6e6e80; } .aliyun-docs-content table.api-reference b, i { color: #181818; } .aliyun-docs-content table.api-reference .collapse { border: none; margin-top: 4px; margin-bottom: 4px; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold { padding: 0; } .aliyun-docs-content table.api-reference .collapse .expandable-title { padding: 0; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold .title { margin-left: 16px; } .aliyun-docs-content table.api-reference .collapse .expandable-title .title { margin-left: 16px; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold i.icon { position: absolute; color: #777; font-weight: 100; } .aliyun-docs-content table.api-reference .collapse .expandable-title i.icon { position: absolute; color: #777; font-weight: 100; } .aliyun-docs-content table.api-reference .collapse.expanded .expandable-content { padding: 10px 14px 10px 14px !important; margin: 0; border: 1px solid #e9e9e9; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold b { font-size: 13px; font-weight: normal; color: #6e6e80; } .aliyun-docs-content table.api-reference .collapse .expandable-title b { font-size: 13px; font-weight: normal; color: #6e6e80; } .aliyun-docs-content table.api-reference .tabbed-content-box { border: none; } .aliyun-docs-content table.api-reference .tabbed-content-box section { padding: 8px 0 !important; } .aliyun-docs-content table.api-reference .tabbed-content-box.mini .tab-box { /\* position: absolute; left: 40px; right: 0; \*/ } .aliyun-docs-content .margin-top-33 { margin-top: 33px !important; } .aliyun-docs-content .two-codeblocks pre { max-height: calc(50vh - 136px) !important; height: auto; } .expandable-content section { border-bottom: 1px solid #e9e9e9; padding-top: 6px; padding-bottom: 4px; } .expandable-content section:last-child { border-bottom: none; } .expandable-content section:first-child { padding-top: 0; }


本文介绍如何通过兼容 OpenAI 格式的 Responses API 调用通义千问模型，包括输入输出参数说明及调用示例。

**相较于OpenAI Chat Completions API 的优势：**

-   **内置工具**：内置联网搜索、网页抓取、代码解释器、文搜图、图搜图、知识库搜索等工具，可在处理复杂任务时获得更优效果，详情参考[工具调用](https://help.aliyun.com/zh/model-studio/tool-calls/)。

-   **更灵活的输入**：支持直接传入字符串作为模型输入，也兼容 Chat 格式的消息数组。

-   **简化上下文管理**：通过传递上一轮响应的 `previous_response_id`，无需手动构建完整的消息历史数组。

-   **便捷的上下文缓存**：只需在请求头中添加 `x-dashscope-session-cache: enable`，服务端即可自动缓存对话上下文，无需改动业务代码即可降低多轮对话的推理延迟与成本，详情参考[Session 缓存](https://help.aliyun.com/zh/model-studio/compatibility-with-openai-responses-api#example-session-cache-title)。


## 兼容性说明与限制

本 API 在接口设计上兼容 OpenAI，以降低开发者迁移成本，但在参数、功能和具体行为上存在差异。

**核心原则：**请求将仅处理本文档明确列出的参数，任何未提及的 OpenAI 参数都会被忽略。

以下是几个关键的差异点，以帮助您快速适配：

-   **部分参数不支持**：不支持部分 OpenAI Responses API 参数，例如异步执行参数`background`（当前仅支持同步调用）等。

-   **思考强度控制**：通过 `reasoning.effort` 参数控制模型的思考强度，具体用法请参考相应参数的说明。


## **华北2（北京）**

SDK 调用配置的`base_url`：`https://dashscope.aliyuncs.com/compatible-mode/v1`

HTTP 请求地址：`POST https://dashscope.aliyuncs.com/compatible-mode/v1/responses`

## **新加坡**

SDK 调用配置的`base_url`：`https://dashscope-intl.aliyuncs.com/compatible-mode/v1`

HTTP 请求地址：`POST https://dashscope-intl.aliyuncs.com/compatible-mode/v1/responses`

## 美国（弗吉尼亚）

SDK 调用配置的`base_url`：`https://dashscope-us.aliyuncs.com/compatible-mode/v1`

HTTP 请求地址：`POST https://dashscope-us.aliyuncs.com/compatible-mode/v1/responses`

**重要**

旧版URL路径 `/api/v2/apps/protocols/compatible-mode/v1/responses` 即将停止维护，请尽快迁移至新版路径 `/compatible-mode/v1/responses`。

| ## **请求体** | ## 基础调用 ## Python ``` import os from openai import OpenAI client = OpenAI( # 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx" api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) response = client.responses.create( model="qwen3.6-plus", input="你能做些什么？" ) # 获取模型回复 print(response.output_text) ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI({ // 若没有配置环境变量，请用百炼API Key将下行替换为：apiKey: "sk-xxx" apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" }); async function main() { const response = await openai.responses.create({ model: "qwen3.6-plus", input: "你能做些什么？" }); // 获取模型回复 console.log(response.output_text); } main(); ``` ## curl ``` curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/responses \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H "Content-Type: application/json" \\ -d '{ "model": "qwen3.6-plus", "input": "你能做些什么？" }' ``` ## 流式输出 ## Python ``` import os from openai import OpenAI client = OpenAI( api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) stream = client.responses.create( model="qwen3.6-plus", input="请简单介绍一下人工智能。", stream=True ) print("开始接收流式输出:") for event in stream: if event.type == 'response.output_text.delta': print(event.delta, end='', flush=True) elif event.type == 'response.completed': print("\\n流式输出完成") print(f"总Token数: {event.response.usage.total_tokens}") ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI({ apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" }); async function main() { const stream = await openai.responses.create({ model: "qwen3.6-plus", input: "请简单介绍一下人工智能。", stream: true }); console.log("开始接收流式输出:"); for await (const event of stream) { if (event.type === 'response.output_text.delta') { process.stdout.write(event.delta); } else if (event.type === 'response.completed') { console.log("\\n流式输出完成"); console.log(`总Token数: ${event.response.usage.total_tokens}`); } } } main(); ``` ## curl ``` curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/responses \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H "Content-Type: application/json" \\ --no-buffer \\ -d '{ "model": "qwen3.6-plus", "input": "请简单介绍一下人工智能。", "stream": true }' ``` ## 多轮对话 ## Python ``` import os from openai import OpenAI client = OpenAI( api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) # 第一轮对话 response1 = client.responses.create( model="qwen3.6-plus", input="我的名字是张三，请记住。" ) print(f"第一轮回复: {response1.output_text}") # 第二轮对话 - 使用 previous_response_id 关联上下文，响应id有效期为7天 response2 = client.responses.create( model="qwen3.6-plus", input="你还记得我的名字吗？", previous_response_id=response1.id ) print(f"第二轮回复: {response2.output_text}") ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI({ apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" }); async function main() { // 第一轮对话 const response1 = await openai.responses.create({ model: "qwen3.6-plus", input: "我的名字是张三，请记住。" }); console.log(`第一轮回复: ${response1.output_text}`); // 第二轮对话 - 使用 previous_response_id 关联上下文，响应id有效期为7天 const response2 = await openai.responses.create({ model: "qwen3.6-plus", input: "你还记得我的名字吗？", previous_response_id: response1.id }); console.log(`第二轮回复: ${response2.output_text}`); } main(); ``` ## 调用内置工具 ## Python ``` import os from openai import OpenAI client = OpenAI( api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) response = client.responses.create( model="qwen3.6-plus", input="帮我找一下阿里云官网，并提取首页的关键信息", # 建议同时开启内置工具以取得最佳效果 tools=[ {"type": "web_search"}, {"type": "code_interpreter"}, {"type": "web_extractor"} ], ) # 取消以下注释查看中间过程输出 # print(response.output) print(response.output_text) ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI({ apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1" }); async function main() { const response = await openai.responses.create({ model: "qwen3.6-plus", input: "帮我找一下阿里云官网，并提取首页的关键信息", tools: [ { type: "web_search" }, { type: "code_interpreter" }, { type: "web_extractor" } ] }); for (const item of response.output) { if (item.type === "reasoning") { console.log("模型正在思考..."); } else if (item.type === "web_search_call") { console.log(`搜索查询: ${item.action.query}`); } else if (item.type === "web_extractor_call") { console.log("正在抽取网页内容..."); } else if (item.type === "message") { console.log(`回复内容: ${item.content[0].text}`); } } } main(); ``` ## curl ``` curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/responses \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H "Content-Type: application/json" \\ -d '{ "model": "qwen3.6-plus", "input": "帮我找一下阿里云官网，并提取首页的关键信息", "tools": [ { "type": "web_search" }, { "type": "code_interpreter" }, { "type": "web_extractor" } ], }' ``` ## 自定义 Function Call ## Python ``` from openai import OpenAI import json import os import random # 初始化客户端 client = OpenAI( # 若没有配置环境变量，请用阿里云百炼API Key将下行替换为：api_key="sk-xxx", api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", ) # 模拟用户问题 USER_QUESTION = "北京天气咋样" # 定义工具列表 tools = [ { "type": "function", "name": "get_current_weather", "description": "当你想查询指定城市的天气时非常有用。", "parameters": { "type": "object", "properties": { "location": { "type": "string", "description": "城市或县区，比如北京市、杭州市、余杭区等。", } }, "required": ["location"], }, } ] # 模拟天气查询工具 def get_current_weather(arguments): weather_conditions = ["晴天", "多云", "雨天"] random_weather = random.choice(weather_conditions) location = arguments["location"] return f"{location}今天是{random_weather}。" # 封装模型响应函数 def get_response(input_data): response = client.responses.create( model="qwen3.6-plus", input=input_data, tools=tools, ) return response # 维护对话上下文 conversation = [{"role": "user", "content": USER_QUESTION}] response = get_response(conversation) function_calls = [item for item in response.output if item.type == "function_call"] # 如果不需要调用工具，直接输出内容 if not function_calls: print(f"助手最终回复：{response.output_text}") else: # 进入工具调用循环 while function_calls: for fc in function_calls: func_name = fc.name arguments = json.loads(fc.arguments) print(f"正在调用工具 [{func_name}]，参数：{arguments}") # 执行工具 tool_result = get_current_weather(arguments) print(f"工具返回：{tool_result}") # 将工具调用和结果成对追加到上下文中 conversation.append( { "type": "function_call", "name": fc.name, "arguments": fc.arguments, "call_id": fc.call_id, } ) conversation.append( { "type": "function_call_output", "call_id": fc.call_id, "output": tool_result, } ) # 携带完整上下文再次调用模型 response = get_response(conversation) function_calls = [ item for item in response.output if item.type == "function_call" ] print(f"助手最终回复：{response.output_text}") ``` ## Node.js ``` import OpenAI from "openai"; // 初始化客户端 const openai = new OpenAI({ // 若没有配置环境变量，请用阿里云百炼API Key将下行替换为：apiKey: "sk-xxx", apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1", }); // 定义工具列表 const tools = [ { type: "function", name: "get_current_weather", description: "当你想查询指定城市的天气时非常有用。", parameters: { type: "object", properties: { location: { type: "string", description: "城市或县区，比如北京市、杭州市、余杭区等。", }, }, required: ["location"], }, }, ]; // 模拟天气查询工具 const getCurrentWeather = (args) => { const weatherConditions = ["晴天", "多云", "雨天"]; const randomWeather = weatherConditions[Math.floor(Math.random() * weatherConditions.length)]; const location = args.location; return `${location}今天是${randomWeather}。`; }; // 封装模型响应函数 const getResponse = async (inputData) => { const response = await openai.responses.create({ model: "qwen3.6-plus", input: inputData, tools: tools, }); return response; }; const main = async () => { const userQuestion = "北京天气"; // 维护对话上下文 const conversation = [{ role: "user", content: userQuestion }]; let response = await getResponse(conversation); let functionCalls = response.output.filter( (item) => item.type === "function_call" ); // 如果不需要调用工具，直接输出内容 if (functionCalls.length === 0) { console.log(`助手最终回复：${response.output_text}`); } else { // 进入工具调用循环 while (functionCalls.length > 0) { for (const fc of functionCalls) { const funcName = fc.name; const args = JSON.parse(fc.arguments); console.log(`正在调用工具 [${funcName}]，参数：`, args); // 执行工具 const toolResult = getCurrentWeather(args); console.log(`工具返回：${toolResult}`); // 将工具调用和结果成对追加到上下文中 conversation.push({ type: "function_call", name: fc.name, arguments: fc.arguments, call_id: fc.call_id, }); conversation.push({ type: "function_call_output", call_id: fc.call_id, output: toolResult, }); } // 携带完整上下文再次调用模型 response = await getResponse(conversation); functionCalls = response.output.filter( (item) => item.type === "function_call" ); } console.log(`助手最终回复：${response.output_text}`); } }; // 启动程序 main().catch(console.error); ``` ## Session 缓存 ## Python ``` import os from openai import OpenAI client = OpenAI( api_key=os.getenv("DASHSCOPE_API_KEY"), base_url="https://dashscope.aliyuncs.com/compatible-mode/v1", # 通过 default_headers 开启 Session 缓存 default_headers={"x-dashscope-session-cache": "enable"} ) # 构造超过 1024 Token 的长文本，确保能触发缓存创建（若未达到1024 Token，后续累积对话上下文超过1024 Token时将触发缓存创建） long_context = "人工智能是计算机科学的一个重要分支，致力于研究和开发能够模拟、延伸和扩展人类智能的理论、方法、技术及应用系统。" * 50 # 第一轮对话 response1 = client.responses.create( model="qwen3.6-plus", input=long_context + "\\n\\n基于以上背景知识，请简短介绍机器学习中的随机森林算法。", ) print(f"第一轮回复: {response1.output_text}") # 第二轮对话：通过 previous_response_id 关联上下文，缓存由服务端自动处理 response2 = client.responses.create( model="qwen3.6-plus", input="它和 GBDT 有什么主要区别？", previous_response_id=response1.id, ) print(f"第二轮回复: {response2.output_text}") # 查看缓存命中情况 usage = response2.usage print(f"输入 Token: {usage.input_tokens}") print(f"缓存命中 Token: {usage.input_tokens_details.cached_tokens}") ``` ## Node.js ``` import OpenAI from "openai"; const openai = new OpenAI({ apiKey: process.env.DASHSCOPE_API_KEY, baseURL: "https://dashscope.aliyuncs.com/compatible-mode/v1", // 通过 defaultHeaders 开启 Session 缓存 defaultHeaders: {"x-dashscope-session-cache": "enable"} }); // 构造超过 1024 Token 的长文本，确保能触发缓存创建（若未达到1024 Token，后续累积对话上下文超过1024 Token时将触发缓存创建） const longContext = "人工智能是计算机科学的一个重要分支，致力于研究和开发能够模拟、延伸和扩展人类智能的理论、方法、技术及应用系统。".repeat(50); async function main() { // 第一轮对话 const response1 = await openai.responses.create({ model: "qwen3.6-plus", input: longContext + "\\n\\n基于以上背景知识，请简短介绍机器学习中的随机森林算法，包括基本原理和应用场景。" }); console.log(`第一轮回复: ${response1.output_text}`); // 第二轮对话：通过 previous_response_id 关联上下文，缓存由服务端自动处理 const response2 = await openai.responses.create({ model: "qwen3.6-plus", input: "它和 GBDT 有什么主要区别？", previous_response_id: response1.id }); console.log(`第二轮回复: ${response2.output_text}`); // 查看缓存命中情况 console.log(`输入 Token: ${response2.usage.input_tokens}`); console.log(`缓存命中 Token: ${response2.usage.input_tokens_details.cached_tokens}`); } main(); ``` ## curl ``` # 第一轮对话 # 长文本重复 50 次以确保超过 1024 Token，触发缓存创建 curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/responses \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H "Content-Type: application/json" \\ -H "x-dashscope-session-cache: enable" \\ -d '{ "model": "qwen3.6-plus", "input": "人工智能是计算机科学的一个重要分支..." }' # 第二轮对话 - 使用上一轮返回的 id 作为 previous_response_id curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/responses \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H "Content-Type: application/json" \\ -H "x-dashscope-session-cache: enable" \\ -d '{ "model": "qwen3.6-plus", "input": "它和 GBDT 有什么主要区别？", "previous_response_id": "第一轮返回的响应id" }' ``` |
| --- | --- |
| **model** `*string*` **（必选）** 模型名称。 支持的模型 ## 中国内地 `qwen3-max`、`qwen3-max-2026-01-23`、`qwen3.6-plus`、`qwen3.6-plus-2026-04-02`、`qwen3.5-plus`、`qwen3.5-plus-2026-04-20`、`qwen3.5-plus-2026-02-15`、`qwen3.6-flash`、`qwen3.6-flash-2026-04-16`、`qwen3.5-flash`、`qwen3.5-flash-2026-02-23`、`qwen3.6-35b-a3b`、`qwen3.5-397b-a17b`、`qwen3.5-122b-a10b`、`qwen3.5-27b`、`qwen3.5-35b-a3b`、`qwen-plus`、`qwen-flash`、`qwen3-coder-plus`、`qwen3-coder-flash`、`qwen3-coder-next` ## 全球 `qwen3.6-plus`、`qwen3.6-plus-2026-04-02`、`qwen3.5-plus`、`qwen3.5-plus-2026-04-20`、`qwen3.5-plus-2026-02-15`、`qwen3.5-flash`、`qwen3.5-flash-2026-02-23`、`qwen3.5-397b-a17b`、`qwen3.5-122b-a10b`、`qwen3.5-27b`、`qwen3.5-35b-a3b` ## 新加坡 `qwen3-max`、`qwen3-max-2026-01-23`、`qwen3.6-plus`、`qwen3.6-plus-2026-04-02`、`qwen3.5-plus`、`qwen3.5-plus-2026-04-20`、`qwen3.5-plus-2026-02-15`、`qwen3.6-flash`、`qwen3.6-flash-2026-04-16`、`qwen3.5-flash`、`qwen3.5-flash-2026-02-23`、`qwen3.6-35b-a3b`、`qwen3.5-397b-a17b`、`qwen3.5-122b-a10b`、`qwen3.5-27b`、`qwen3.5-35b-a3b`、`qwen-plus`、`qwen-flash`、`qwen3-coder-plus`、`qwen3-coder-flash`、`qwen3-coder-next` |
| **input** `*string 或 array*` **（必选）** 模型输入，支持以下格式： - `string`：纯文本，如 `"你好"`。 - `array`：消息数组，按对话顺序排列。 **array 输入项类型** **EasyInputMessage** `*object*` 通过 role 区分消息类型，通过content传递消息内容。 **属性** **role** `*string*` **（必选）** 消息角色，可选值：`user`、`assistant`、`system`、`developer`。 **content** `*string 或 array*` **（必选）** 消息内容。若输入为纯文本，则为 string 类型；若输入为结构化内容数组，则为 array 类型。role 为 `system`/`developer` 时，array 元素类型为 `input_text`；role 为 `user` 时，array 元素类型为 `input_text` 或 `input_image`；role 为 `assistant` 时，array 元素类型为 `output_text`。 > 当前 Responses API 暂不支持传入视频或语音，您可以通过[Chat Completions API](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)或[DashScope API](https://help.aliyun.com/zh/model-studio/qwen-api-via-dashscope)传入。 **content 数组元素** **type** `*string*` **（必选）** 可选值：`input_text`（文本输入）、`input_image`（图片输入，仅 user 角色）、`output_text`（助手回复，仅 assistant 角色）。 **text** `*string*` 文本内容。当 type 为 `input_text` 或 `output_text` 时必填。 **image\\_url** `*string*` 图片的公网 URL。当 type 为 `input_image` 时必填。 **type** `*string*` （可选） 固定为 `message`。 **ResponseOutputMessage** `*object*` （可选） 模型的输出消息对象。可直接将上一轮响应的 output 中的 message 项传回 input，用于多轮对话场景。与 EasyInputMessage 的区别在于它携带了完整的输出结构（含 id、status 和结构化 content）。 **属性** **type** `*string*` **（必选）** 固定为 `message`。 **id** `*string*` **（必选）** 输出消息的唯一标识，来自上一轮响应。 **role** `*string*` **（必选）** 固定为 `assistant`。 **status** `*string*` **（必选）** 消息状态，可选值：`in_progress`、`completed`、`incomplete`。 **content** `*array*` **（必选）** 内容数组，元素为 output\\_text 类型对象。 **属性** **type** `*string*` **（必选）** 固定为 `output_text`。 **text** `*string*` **（必选）** 回复文本。 **annotations** `*array*` （可选） 标注信息。 **Function Call** `*object*` （可选） 模型决定调用外部工具时生成的结构化指令。 **属性** **type** `*string*` **（必选）** 固定为 `function_call`。 **id** `*string*` （可选） Function Call 的唯一标识，来自上一轮响应。 **name** `*string*` **（必选）** 工具函数名称。 **arguments** `*string*` **（必选）** 工具调用参数，JSON 字符串格式。 **call\\_id** `*string*` **（必选）** 工具调用的标识符，需与模型返回的 `call_id` 一致。 **status** `*string*` （可选） 状态，可选值：`in_progress`、`completed`、`incomplete`。 **Function Call Output** `*object*` （可选） 工具调用的输出结果。在消息列表中**必须**紧跟对应的 `function_call` 消息，否则会报错。 **属性** **type** `*string*` **（必选）** 固定为 `function_call_output`。 **id** `*string*` （可选） Function Call Output 的唯一标识。 **call\\_id** `*string*` **（必选）** 工具调用的标识符，需与模型返回的 `call_id` 一致。 **output** `*string*` **（必选）** 工具函数的执行结果。 **status** `*string*` （可选） 状态，可选值：`in_progress`、`completed`、`incomplete`。 **Reasoning** `*object*` （可选） 模型的思考内容。可直接将上一轮响应的 output 中的 reasoning 项传回 input，用于在多轮对话中传递思考内容。 **属性** **type** `*string*` **（必选）** 固定为 `reasoning`。 **id** `*string*` **（必选）** 思考内容的唯一标识，来自上一轮响应。 **summary** `*array*` **（必选）** 思考摘要内容。 **属性** **type** `*string*` **（必选）** 固定为 `summary_text`。 **text** `*string*` **（必选）** 摘要文本。 **status** `*string*` （可选） 状态，可选值：`in_progress`、`completed`、`incomplete`。 |
| **instructions** `*string*` （可选） 作为系统指令插入到上下文的起始位置。使用 `previous_response_id` 时，上一轮指定的 `instructions` 不会传入本轮上下文。 |
| **previous\\_response\\_id** `*string*` （可选） 上一个响应的唯一 ID，当前响应`id`有效期为7天。使用此参数可创建多轮对话，服务端会自动检索并组合该轮次的输入与输出作为上下文。当同时提供 `input` 消息数组和 `previous_response_id` 时，`input` 中的新消息会追加到历史上下文之后。不能与 `conversation` 同时使用。 |
| **conversation** `*string*` （可选） 当前响应所属的会话（参考[Conversations API](https://help.aliyun.com/zh/model-studio/openai-compatible-conversations)）。会话中的历史项会自动作为上下文传入本次请求，本次请求的输入和输出也会在响应完成后自动添加到会话中。不能与 `previous_response_id` 同时使用。 |
| **stream** `*boolean*` （可选）默认值为 `false` 是否开启流式输出。设置为 `true` 时，模型响应数据将实时流式返回给客户端。 |
| **store** `*boolean*` （可选）默认值为 `true` 是否储存本次会话生成的模型响应。 - `false`：不储存，对话内容不能被 `previous_response_id` 和后续 API 使用。 - `true`：储存，当前模型响应可被 `previous_response_id` 和后续 API 使用。 |
| **tools** `*array*` （可选） 模型在生成响应时可调用的工具数组。支持内置工具和自定义 function 工具，可混合使用。 > 为了获得最佳回复效果，建议同时开启 `code_interpreter`、`web_search` 和 `web_extractor` 工具。 **属性** **web\\_search** 联网搜索工具，允许模型搜索互联网上的最新信息。相关文档：[联网搜索](https://help.aliyun.com/zh/model-studio/web-search) **属性** **type** `*string*` **（必选）** 固定为`web_search`。 使用示例：`[{"type": "web_search"}]` **web\\_extractor** 网页抽取工具，允许模型访问并提取网页内容。当前必须配合`web_search`工具一起使用。`qwen3-max`、`qwen3-max-2026-01-23`需要同时开启思考模式。相关文档：[网页抓取](https://help.aliyun.com/zh/model-studio/web-extractor) **属性** **type** `*string*` **（必选）** 固定为`web_extractor`。 使用示例：`[{"type": "web_search"}, {"type": "web_extractor"}]` **code\\_interpreter** 代码解释器工具，允许模型执行代码并返回结果，支持数据分析。`qwen3-max`、`qwen3-max-2026-01-23`需要同时开启思考模式。相关文档：[代码解释器](https://help.aliyun.com/zh/model-studio/qwen-code-interpreter) **属性** **type** `*string*` **（必选）** 固定为`code_interpreter`。 使用示例：`[{"type": "code_interpreter"}]` **web\\_search\\_image** 根据文本描述搜索图片。相关文档：[文搜图](https://help.aliyun.com/zh/model-studio/web-search-image) **属性** **type** `*string*` **（必选）** 固定为`web_search_image`。 使用示例：`[{"type": "web_search_image"}]` **image\\_search** 根据图片搜索相似或相关图片，输入中需要包含图片的URL。相关文档：[图搜图](https://help.aliyun.com/zh/model-studio/image-search) **属性** **type** `*string*` **（必选）** 固定为`image_search`。 使用示例：`[{"type": "image_search"}]` **file\\_search** 在已上传或关联的知识库中搜索。相关文档：[知识检索](https://help.aliyun.com/zh/model-studio/file-search) **属性** **type** `*string*` **（必选）** 固定为`file_search`。 **vector\\_store\\_ids** `*array*` **（必选）** 要检索的知识库 ID。**当前仅支持传入一个知识库 ID**。 使用示例：`[{"type": "file_search", "vector_store_ids": ["your_knowledge_base_id"]}]` **MCP调用** 通过 MCP（Model Context Protocol）调用外部服务，相关文档：[MCP](https://help.aliyun.com/zh/model-studio/mcp) **属性** **type** `*string*` **（必选）** 固定为`mcp`。 **server\\_protocol** `*string*` **（必选）** 与 MCP 服务的通信协议，如 `"sse"` **server\\_label** `*string*` **（必选）** 服务标签，用于标识该 MCP 服务。 **server\\_description** `*string*` （可选） 服务描述，帮助模型理解其功能与适用场景。 **server\\_url** `*string*` **（必选）** MCP 服务端点的 URL。 **headers** `*object*` （可选） 请求头，用于携带身份验证等信息，如 `Authorization`。 使用示例： ``` mcp_tool = { "type": "mcp", "server_protocol": "sse", "server_label": "amap-maps", "server_description": "高德地图MCP Server现已覆盖15大核心接口，提供全场景覆盖的地理信息服务，包括生成专属地图、导航到目的地、打车、地理编码、逆地理编码、IP定位、天气查询、骑行路径规划、步行路径规划、驾车路径规划、公交路径规划、距离测量、关键词搜索、周边搜索、详情搜索等。", "server_url": "https://dashscope.aliyuncs.com/api/v1/mcps/amap-maps/sse", "headers": { "Authorization": "Bearer <your-mcp-server-token>" } } ``` **自定义工具** **function** 自定义函数工具，允许模型调用您定义的函数。当模型判断需要调用工具时，响应会返回 `function_call` 类型的输出。相关文档：[Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling) **属性** **type** `*string*` **（必选）** 必须设置为`function`。 **name** `*string*` **（必选）** 工具名称。仅允许字母、数字、下划线（`_`）和短划线（`-`），最长 64 个 Token。 **description** `*string*` **（必选）** 工具描述信息，帮助模型判断何时以及如何调用该工具。 **parameters** `*object*` （可选） 工具的参数描述，需要是一个合法的 [JSON Schema](https://json-schema.org/understanding-json-schema)。若`parameters`参数为空，表示该工具没有入参（如时间查询工具）。 > 为提高工具调用的准确性，建议传入 `parameters`。 使用示例： ``` [{ "type": "function", "name": "get_weather", "description": "获取指定城市的天气信息", "parameters": { "type": "object", "properties": { "city": { "type": "string", "description": "城市名称" } }, "required": ["city"] } }] ``` |
| **tool\\_choice** `*string or object*` （可选）默认值为 `auto` 控制模型如何选择和调用工具。此参数支持两种赋值格式：**字符串模式**和**对象模式**。 **字符串模式** - `auto`：模型自动决定是否调用工具。 - `none`：禁止模型调用任何工具。 - `required`：强制模型调用工具（仅当 `tools` 列表中只有一个工具时可用）。 **对象模式** 为模型设定可用的工具范围，仅限在预定义的工具列表中进行选择和调用。 **属性** **mode** `*string*` **（必选）** - `auto`：模型自动决定是否调用工具。 - `required`：强制模型调用工具（仅当 `tools` 列表中只有一个工具时可用）。 **tools** `*array*`**（必选）** 一个包含工具定义的列表，模型将被允许调用这些工具。 ``` [ { "type": "function", "name": "get_weather" } ] ``` **type** `*string*` **（必选）** 允许的工具配置类型，固定为 `allowed_tools`。 |
| **temperature** `*float*` （可选） 采样温度，控制模型生成文本的多样性。 temperature越高，生成的文本更多样，反之，生成的文本更确定。 取值范围： \\[0, 2) temperature与top\\_p均可以控制生成文本的多样性，建议只设置其中一个值。更多说明，请参见[概述](https://help.aliyun.com/zh/model-studio/text-generation#ad7b336bec5fw)。 |
| **top\\_p** `*float*` （可选） 核采样的概率阈值，控制模型生成文本的多样性。 top\\_p越高，生成的文本更多样。反之，生成的文本更确定。 取值范围：（0,1.0\\] temperature与top\\_p均可以控制生成文本的多样性，建议只设置其中一个值。更多说明，请参见[概述](https://help.aliyun.com/zh/model-studio/text-generation#ad7b336bec5fw)。 |     |
| **enable\\_thinking** `*boolean*` （可选） 是否开启思考模式。开启后，模型会在回复前进行思考，思考内容将通过 `reasoning` 类型的输出项返回。开启思考模式时，**建议开启**内置工具，以在处理复杂任务时获得最佳的模型效果。 可选值： - `true`：开启 - `false`：不开启 不同模型的默认值：[支持的模型](https://help.aliyun.com/zh/model-studio/deep-thinking#78286fdc35hlw) > 该参数非OpenAI标准参数。Python SDK 通过 `extra_body={"enable_thinking": True}` 传递；Node.js SDK 和 curl 直接使用 `enable_thinking: true` 作为顶层参数。建议使用 `reasoning.effort` 替代，`enable_thinking` 后续将不再支持。 |     |
| **reasoning** `*object*` （可选） 控制模型的思考强度。模型会在回复前进行思考，思考内容将通过 `reasoning` 类型的输出项返回。 **属性** **effort** `*string*` （可选）：思考强度档位，默认值为 `medium`。 - `none`：关闭思考，直接回答 - `minimal`：最小化思考，最快速响应 - `low`：轻度思考，侧重快速响应 - `medium`（默认值）：中度思考，平衡速度与思考深度 - `high`：深度思考，侧重处理复杂专业问题 > `reasoning.effort` 的优先级高于 `enable_thinking`，建议优先使用 `reasoning.effort`，`enable_thinking` 后续将不再支持。 |     |

| ## **Response 响应对象（非流式输出）** | ``` { "created_at": 1771165743, "id": "c9f9c06b-032d-4525-a422-ac8ab5eccxxx", "model": "qwen3.6-plus", "object": "response", "output": [ { "content": [ { "annotations": [], "text": "你好！我是 Qwen3.5，阿里巴巴最新推出的通义千问大语言模型，具备强大的语言理解、逻辑推理、代码生成及多模态处理能力，旨在为用户提供精准高效的智能服务。", "type": "output_text" } ], "id": "msg_544b2907-e88e-40d2-9a83-c30d6d1f9xxx", "role": "assistant", "status": "completed", "type": "message" } ], "parallel_tool_calls": false, "status": "completed", "tool_choice": "auto", "tools": [], "usage": { "input_tokens": 55, "input_tokens_details": { "cached_tokens": 0 }, "output_tokens": 43, "output_tokens_details": { "reasoning_tokens": 0 }, "total_tokens": 98, "x_details": [ { "input_tokens": 55, "output_tokens": 43, "total_tokens": 98, "x_billing_type": "response_api" } ] } } ``` |
| --- | --- |
| **id** `*string*` 本次响应的唯一标识符，为 UUID 格式的字符串，有效期为7天。可用于 `previous_response_id` 参数以创建多轮对话。 |
| **created\\_at** `*integer*` 本次请求的 Unix 时间戳（秒）。 |
| **object** `*string*` 对象类型，固定为 `response`。 |
| **status** `*string*` 响应生成的状态。枚举值： - `completed`：生成完成 - `failed`：生成失败 - `in_progress`：生成中 - `cancelled`：已取消 - `queued`：请求排队中 - `incomplete`：生成不完整 |
| **model** `*string*` 用于生成响应的模型 ID。 |
| **output** `*array*` 模型生成的输出项数组。数组中的元素类型和顺序取决于模型的响应。 **数组元素属性** **type** `*string*` 输出项类型。枚举值： - `message`：消息类型，包含模型最终生成的回复内容。 - `reasoning`：推理类型，设置 `reasoning.effort`（非 `none`）或开启思考模式时返回。推理 Token 会被计入 `output_tokens_details.reasoning_tokens` 中，按推理 Token 计费。 - `function_call`：函数调用类型，使用自定义 `function` 工具时返回。需要处理函数调用并返回结果。 - `web_search_call`：搜索调用类型，使用 `web_search` 工具时返回。 - `code_interpreter_call`：代码执行类型，使用 `code_interpreter` 工具时返回。 - `web_extractor_call`：网页抽取类型，使用 `web_extractor` 工具时返回。需要配合 `web_search` 工具一起使用。 - `web_search_image_call`：文搜图调用类型，使用 `web_search_image` 工具时返回。包含搜索到的图片列表。 - `image_search_call`：图搜图调用类型，使用 `image_search` 工具时返回。包含搜索到的相似图片列表。 - `mcp_call`：MCP 调用类型，使用 `mcp` 工具时返回。包含 MCP 服务的调用结果。 - `file_search_call`：知识库搜索调用类型，使用 `file_search` 工具时返回。包含知识库的检索查询和结果。 **id** `*string*` 输出项的唯一标识符。所有类型的输出项都包含此字段。 **role** `*string*` 消息角色，固定为 `assistant`。仅当 `type` 为 `message` 时存在。 **status** `*string*` 输出项状态。可选值：`completed`（完成）、`in_progress`（生成中）。当 `type` 不为`reasoning`时存在。 **name** `*string*` 工具或函数名称。当 `type` 为 `function_call`、`web_search_image_call`、`image_search_call`、`mcp_call` 时存在。 对于 `web_search_image_call` 和 `image_search_call`，值分别固定为 `"web_search_image"` 和 `"image_search"`。 对于 `mcp_call`，值为 MCP 服务中被调用的具体函数名（如 `amap-maps-maps_geo`）。 **arguments** `*string*` 工具调用的参数，JSON 字符串格式。当 `type` 为 `function_call`、`web_search_image_call`、`image_search_call`、`mcp_call` 时存在。使用前需要通过 `JSON.parse()` 解析。不同工具类型的 arguments 内容： - `web_search_image_call`：`{"queries": ["搜索关键词1", "搜索关键词2"]}`，其中 `queries` 为模型根据用户输入自动生成的搜索关键词列表。 - `image_search_call`：`{"img_idx": 0, "bbox": [0, 0, 1000, 1000]}`，其中 `img_idx` 为输入图片的索引（从 0 开始），`bbox` 为搜索区域的边界框坐标 \\[x1, y1, x2, y2\\]，坐标范围 0-1000。 - `function_call`：按用户定义的函数参数 schema 生成的参数对象。 - `mcp_call`：MCP 服务中被调用函数的参数对象。 **call\\_id** `*string*` 函数调用的唯一标识符。仅当 `type` 为 `function_call` 时存在。在返回函数调用结果时，需要通过此 ID 关联请求与响应。 **content** `*array*` 消息内容数组。仅当 `type` 为 `message` 时存在。 **数组元素属性** **type** `*string*` 内容类型，固定为 `output_text`。 **text** `*string*` 模型生成的文本内容。 **annotations** `*array*` 文本注释数组。通常为空数组。 **summary** `*array*` 推理摘要数组。仅当 `type` 为 `reasoning` 时存在。每个元素包含 `type`（值为 `summary_text`）和 `text`（摘要文本）字段。 **action** `*object*` 搜索动作信息。仅当 `type` 为 `web_search_call` 时存在。 **属性** **query** `*string*` 搜索查询关键词。 **type** `*string*` 搜索类型，固定为 `search`。 **sources** `*array*` 搜索来源列表。每个元素包含 `type`和 `url`字段。 **code** `*string*` 模型生成并执行的代码。仅当 `type` 为 `code_interpreter_call` 时存在。 **outputs** `*array*` 代码执行输出数组。仅当 `type` 为 `code_interpreter_call` 时存在。每个元素包含 `type`（值为 `logs`）和 `logs`（代码执行日志）字段。 **container\\_id** `*string*` 代码解释器容器标识符。仅当 `type` 为 `code_interpreter_call` 时存在。用于关联同一会话中的多次代码执行。 **goal** `*string*` 抽取目标描述，说明需要从网页中提取哪些信息。仅当 `type` 为 `web_extractor_call` 时存在。 **output** `*string*` 工具调用的输出结果，字符串格式。 - 当 `type` 为 `web_extractor_call` 时为网页抽取的内容摘要 - 当 `type` 为 `web_search_image_call` 或 `image_search_call` 时为 JSON 字符串，包含图片搜索结果数组，每个元素包含 `title`（图片标题）、`url`（图片 URL）和 `index`（序号）字段 - 当 `type` 为 `mcp_call` 时为 MCP 服务返回的 JSON 字符串结果。 **urls** `*array*` 被抽取的网页 URL 列表。仅当 `type` 为 `web_extractor_call` 时存在。 **server\\_label** `*string*` MCP 服务标签。仅当 `type` 为 `mcp_call` 时存在。标识本次调用所使用的 MCP 服务。 **queries** `*array*` 知识库检索使用的查询列表。仅当 `type` 为 `file_search_call` 时存在。数组元素为字符串，表示模型生成的搜索查询词。 **results** `*array*` 知识库检索结果数组。仅当 `type` 为 `file_search_call` 时存在。 **数组元素属性** **file\\_id** `*string*` 匹配文档的文件 ID。 **filename** `*string*` 匹配文档的文件名。 **score** `*float*` 匹配相关度评分，取值范围 0-1，值越大表示相关度越高。 **text** `*string*` 匹配到的文档内容片段。 |
| **usage** `*object*` 本次请求的 Token 消耗信息。 **属性** **input\\_tokens** `*integer*` 输入的 Token 数。 **output\\_tokens** `*integer*` 模型输出的 Token 数。 **total\\_tokens** `*integer*` 消耗的总 Token 数，为 input\\_tokens 与 output\\_tokens 的总和。 **input\\_tokens\\_details** `*object*` 输入 Token 的细粒度分类。 **属性** **cached\\_tokens** `*integer*` 命中缓存的 Token 数。详情请参见[上下文缓存](https://help.aliyun.com/zh/model-studio/context-cache)。 **output\\_tokens\\_details** `*object*` 输出 Token 的细粒度分类。 **属性** **reasoning\\_tokens** `*integer*` 思考过程 Token 数。 **x\\_details** `*array*` **属性** **input\\_tokens** `*integer*` 输入的 Token 数。 **output\\_tokens** `*integer*` 模型输出的 Token 数。 **total\\_tokens** `*integer*` 消耗的总 Token 数，为 input\\_tokens 与 output\\_tokens 的总和。 **x\\_billing\\_type** `*string*` 固定为`response_api`。 **prompt\\_tokens\\_details** `*object*` 启用 Session 缓存后返回。输入 Token 的缓存详情。 **属性** **cached\\_tokens** `*integer*` 命中缓存的 Token 数。 **cache\\_creation\\_input\\_tokens** `*integer*` 本次请求新创建缓存的 Token 数。 **cache\\_creation** `*object*` 缓存创建详情。 **属性** **ephemeral\\_5m\\_input\\_tokens** `*integer*` 5 分钟临时缓存新创建的 Token 数。 **cache\\_type** `*string*` 缓存类型，固定为`ephemeral`。 **x\\_tools** `*object*` 工具使用统计信息。当使用内置工具时，包含各工具的调用次数。 示例：`{"web_search": {"count": 1}}` |
| **error** `*object*` 当模型生成响应失败时返回的错误对象。成功时为 `null`。 |
| **tools** `*array*` 回显请求中 `tools` 参数的完整内容，结构与请求体中的 `tools` 参数相同。 |
| **tool\\_choice** `*string*` 回显请求中 `tool_choice` 参数的值，枚举值为 `auto`、`none`、`required`。 |

| ## **Response 响应 chunk 对象（流式输出）** | ## 基础调用 ``` // response.created - 响应创建 {"response":{"id":"428c90e9-9cd6-90a6-9726-c02b08ebexxx","created_at":1769082930,"object":"response","status":"queued",...},"sequence_number":0,"type":"response.created"} // response.in_progress - 响应进行中 {"response":{"id":"428c90e9-9cd6-90a6-9726-c02b08ebexxx","status":"in_progress",...},"sequence_number":1,"type":"response.in_progress"} // response.output_item.added - 新增输出项 {"item":{"id":"msg_bcb45d66-fc34-46a2-bb56-714a51e8exxx","content":[],"role":"assistant","status":"in_progress","type":"message"},"output_index":0,"sequence_number":2,"type":"response.output_item.added"} // response.content_part.added - 新增内容块 {"content_index":0,"item_id":"msg_bcb45d66-fc34-46a2-bb56-714a51e8exxx","output_index":0,"part":{"annotations":[],"text":"","type":"output_text","logprobs":null},"sequence_number":3,"type":"response.content_part.added"} // response.output_text.delta - 增量文本（多次触发） {"content_index":0,"delta":"人工智能","item_id":"msg_bcb45d66-fc34-46a2-bb56-714a51e8exxx","logprobs":[],"output_index":0,"sequence_number":4,"type":"response.output_text.delta"} {"content_index":0,"delta":"（Artificial Intelligence，","item_id":"msg_bcb45d66-fc34-46a2-bb56-714a51e8exxx","logprobs":[],"output_index":0,"sequence_number":6,"type":"response.output_text.delta"} // response.output_text.done - 文本完成 {"content_index":0,"item_id":"msg_bcb45d66-fc34-46a2-bb56-714a51e8exxx","logprobs":[],"output_index":0,"sequence_number":53,"text":"人工智能（Artificial Intelligence，简称 AI）是指由计算机系统模拟人类智能行为的技术和科学...","type":"response.output_text.done"} // response.content_part.done - 内容块完成 {"content_index":0,"item_id":"msg_bcb45d66-fc34-46a2-bb56-714a51e8exxx","output_index":0,"part":{"annotations":[],"text":"...完整文本...","type":"output_text","logprobs":null},"sequence_number":54,"type":"response.content_part.done"} // response.output_item.done - 输出项完成 {"item":{"id":"msg_bcb45d66-fc34-46a2-bb56-714a51e8exxx","content":[{"annotations":[],"text":"...完整文本...","type":"output_text","logprobs":null}],"role":"assistant","status":"completed","type":"message"},"output_index":0,"sequence_number":55,"type":"response.output_item.done"} // response.completed - 响应完成（包含完整响应和 usage） {"response":{"id":"428c90e9-9cd6-90a6-9726-c02b08ebexxx","created_at":1769082930,"model":"qwen3-max-2026-01-23","object":"response","output":[...],"status":"completed","usage":{"input_tokens":37,"output_tokens":243,"total_tokens":280,...}},"sequence_number":56,"type":"response.completed"} ``` ## 网页抓取 ``` id:1 event:response.created :HTTP_STATUS/200 data:{"sequence_number":0,"type":"response.created","response":{"output":[],"parallel_tool_calls":false,"created_at":1769435906,"tool_choice":"auto","model":"","id":"863df8d9-cb29-4239-a54f-3e15a2427xxx","tools":[],"object":"response","status":"queued"}} id:2 event:response.in_progress :HTTP_STATUS/200 data:{"sequence_number":1,"type":"response.in_progress","response":{"output":[],"parallel_tool_calls":false,"created_at":1769435906,"tool_choice":"auto","model":"","id":"863df8d9-cb29-4239-a54f-3e15a2427xxx","tools":[],"object":"response","status":"in_progress"}} id:3 event:response.output_item.added :HTTP_STATUS/200 data:{"sequence_number":2,"item":{"summary":[],"type":"reasoning","id":"msg_5bd0c6df-19b8-4a04-bc00-8042a224exxx"},"output_index":0,"type":"response.output_item.added"} id:4 event:response.reasoning_summary_text.delta :HTTP_STATUS/200 data:{"delta":"用户想要我：\\n1. 搜索阿里云官网\\n2. 提取官网首页的关键信息\\n\\n我需要先搜索阿里云官网的URL，然后使用web_extractor工具访问官网并提取关键信息。","sequence_number":3,"output_index":0,"type":"response.reasoning_summary_text.delta","item_id":"msg_5bd0c6df-19b8-4a04-bc00-8042a224exxx","summary_index":0} id:14 event:response.reasoning_summary_text.done :HTTP_STATUS/200 data:{"sequence_number":13,"text":"用户想要我：\\n1. 搜索阿里云官网\\n2. 提取官网首页的关键信息\\n\\n我需要先搜索阿里云官网的URL，然后使用web_extractor工具访问官网并提取关键信息。","output_index":0,"type":"response.reasoning_summary_text.done","item_id":"msg_5bd0c6df-19b8-4a04-bc00-8042a224exxx","summary_index":0} id:15 event:response.output_item.done :HTTP_STATUS/200 data:{"sequence_number":14,"item":{"summary":[{"type":"summary_text","text":"用户想要我：\\n1. 搜索阿里云官网\\n2. 提取官网首页的关键信息\\n\\n我需要先搜索阿里云官网的URL，然后使用web_extractor工具访问官网并提取关键信息。"}],"type":"reasoning","id":"msg_5bd0c6df-19b8-4a04-bc00-8042a224exxx"},"output_index":1,"type":"response.output_item.done"} id:16 event:response.output_item.added :HTTP_STATUS/200 data:{"sequence_number":15,"item":{"action":{"type":"search","query":"Web search"},"id":"msg_a8a686b1-0a57-40e1-bb55-049a89cd4xxx","type":"web_search_call","status":"in_progress"},"output_index":1,"type":"response.output_item.added"} id:17 event:response.web_search_call.in_progress :HTTP_STATUS/200 data:{"sequence_number":16,"output_index":1,"type":"response.web_search_call.in_progress","item_id":"msg_a8a686b1-0a57-40e1-bb55-049a89cd4xxx"} id:19 event:response.web_search_call.completed :HTTP_STATUS/200 data:{"sequence_number":18,"output_index":1,"type":"response.web_search_call.completed","item_id":"msg_a8a686b1-0a57-40e1-bb55-049a89cd4xxx"} id:20 event:response.output_item.done :HTTP_STATUS/200 data:{"sequence_number":19,"item":{"action":{"sources":[{"type":"url","url":"https://cn.aliyun.com/"},{"type":"url","url":"https://www.aliyun.com/"}],"type":"search","query":"Web search"},"id":"msg_a8a686b1-0a57-40e1-bb55-049a89cd4xxx","type":"web_search_call","status":"completed"},"output_index":1,"type":"response.output_item.done"} id:33 event:response.output_item.added :HTTP_STATUS/200 data:{"sequence_number":32,"item":{"urls":["https://cn.aliyun.com/"],"goal":"提取阿里云官网首页的关键信息，包括：公司定位/简介、核心产品与服务、主要业务板块、特色功能/解决方案、最新动态/活动、免费试用/优惠信息、导航菜单结构等","id":"msg_8c2cf651-48a5-460c-aa7a-bea5b09b4xxx","type":"web_extractor_call","status":"in_progress"},"output_index":3,"type":"response.output_item.added"} id:34 event:response.output_item.done :HTTP_STATUS/200 data:{"sequence_number":33,"item":{"output":"The useful information in https://cn.aliyun.com/ for user goal 提取阿里云官网首页的关键信息，包括：公司定位/简介、核心产品与服务、主要业务板块、特色功能/解决方案、最新动态/活动、免费试用/优惠信息、导航菜单结构等 as follows: \\n\\nEvidence in page: \\n## 通义大模型，企业拥抱 AI 时代首选\\n\\n## 完整的产品体系，为企业打造技术创新的云\\n\\n全部云产品## 依托大模型与云计算协同发展，让 AI 触手可及\\n\\n全部 AI 解决方案\\n\\nSummary: \\nAlibaba Cloud positions itself as a leading enterprise AI solution provider centered around the Tongyi large model...","urls":["https://cn.aliyun.com/"],"goal":"提取阿里云官网首页的关键信息，包括：公司定位/简介、核心产品与服务、主要业务板块、特色功能/解决方案、最新动态/活动、免费试用/优惠信息、导航菜单结构等","id":"msg_8c2cf651-48a5-460c-aa7a-bea5b09b4xxx","type":"web_extractor_call","status":"completed"},"output_index":3,"type":"response.output_item.done"} id:50 event:response.output_item.added :HTTP_STATUS/200 data:{"sequence_number":50,"item":{"content":[{"type":"text","text":""}],"type":"message","id":"msg_final","role":"assistant"},"output_index":5,"type":"response.output_item.added"} id:51 event:response.output_text.delta :HTTP_STATUS/200 data:{"delta":"我已经找到阿里云官网并提取了首页的关键信息：\\n\\n","sequence_number":51,"output_index":5,"type":"response.output_text.delta"} id:60 event:response.completed :HTTP_STATUS/200 data:{"type":"response.completed","response":{"id":"863df8d9-cb29-4239-a54f-3e15a2427xxx","status":"completed","usage":{"input_tokens":45,"output_tokens":320,"total_tokens":365}}} ``` ## 文搜图 ``` // 1. response.created - 响应创建 id:1 event:response.created data:{"sequence_number":0,"type":"response.created","response":{"output":[],"status":"queued",...}} // 2. response.in_progress - 响应进行中 id:2 event:response.in_progress data:{"sequence_number":1,"type":"response.in_progress","response":{"status":"in_progress",...}} // 3. response.output_item.added - 推理开始（reasoning） id:3 event:response.output_item.added data:{"sequence_number":2,"item":{"summary":[],"type":"reasoning","id":"msg_xxx"},"output_index":0,"type":"response.output_item.added"} // 4. response.reasoning_summary_text.delta - 推理摘要增量 id:4 event:response.reasoning_summary_text.delta data:{"delta":"用户想要找一张猫咪图片。我需要使用web_search_image工具来搜索...","sequence_number":3,"output_index":0,"type":"response.reasoning_summary_text.delta","item_id":"msg_xxx","summary_index":0} // 5. response.reasoning_summary_text.done - 推理摘要完成 id:10 event:response.reasoning_summary_text.done data:{"sequence_number":9,"text":"用户想要找一张猫咪图片。我需要使用web_search_image工具来搜索猫咪图片。","output_index":0,"type":"response.reasoning_summary_text.done","item_id":"msg_xxx","summary_index":0} // 6. response.output_item.done - 推理项完成 id:11 event:response.output_item.done data:{"sequence_number":10,"item":{"summary":[{"type":"summary_text","text":"..."}],"type":"reasoning","id":"msg_xxx"},"output_index":0,"type":"response.output_item.done"} // 7. response.output_item.added - 文搜图工具调用开始（status: in_progress，此时已包含 name 和 arguments） id:12 event:response.output_item.added data:{"sequence_number":11,"item":{"name":"web_search_image","arguments":"{\\"queries\\": [\\"猫咪图片\\", \\"cute cat\\"]}","id":"msg_xxx","type":"web_search_image_call","status":"in_progress"},"output_index":1,"type":"response.output_item.added"} // 8. response.output_item.done - 文搜图工具调用完成（包含完整的 output 搜索结果） id:13 event:response.output_item.done data:{"sequence_number":12,"item":{"name":"web_search_image","output":"[{\\"title\\": \\"可爱的小猫咪...\\", \\"url\\": \\"https://example.com/cat.jpg\\", \\"index\\": 1}, ...]","arguments":"{\\"queries\\": [\\"猫咪图片\\", \\"cute cat\\"]}","id":"msg_xxx","type":"web_search_image_call","status":"completed"},"output_index":1,"type":"response.output_item.done"} // 9-12. 第二轮推理 + 最终消息输出（与基础调用相同） // response.output_item.added (reasoning) → reasoning_summary_text.delta/done → response.output_item.done (reasoning) // response.output_item.added (message) → response.content_part.added → response.output_text.delta → response.output_text.done → response.content_part.done → response.output_item.done (message) // 13. response.completed - 响应完成 id:118 event:response.completed data:{"sequence_number":117,"type":"response.completed","response":{"output":[...],"status":"completed","usage":{"input_tokens":7895,"output_tokens":318,"total_tokens":8213,"x_tools":{"web_search_image":{"count":1}}}}} ``` ## 图搜图 ``` // 1-6. 推理阶段（与文搜图相同） // 7. response.output_item.added - 图搜图工具调用开始 // 注意 arguments 中包含 img_idx（图片索引）和 bbox（搜索区域边界框） id:29 event:response.output_item.added data:{"sequence_number":29,"item":{"name":"image_search","arguments":"{\\"img_idx\\": 0, \\"bbox\\": [0, 0, 1000, 1000]}","id":"msg_xxx","type":"image_search_call","status":"in_progress"},"output_index":1,"type":"response.output_item.added"} // 8. response.output_item.done - 图搜图工具调用完成 id:30 event:response.output_item.done data:{"sequence_number":30,"item":{"name":"image_search","output":"[{\\"title\\": \\"水墨山背景...\\", \\"url\\": \\"https://example.com/landscape.jpg\\", \\"index\\": 1}, ...]","arguments":"{\\"img_idx\\": 0, \\"bbox\\": [0, 0, 1000, 1000]}","id":"msg_xxx","type":"image_search_call","status":"completed"},"output_index":1,"type":"response.output_item.done"} // 9-12. 第二轮推理 + 最终消息输出（与基础调用相同） // 13. response.completed id:408 event:response.completed data:{"sequence_number":407,"type":"response.completed","response":{"output":[...],"status":"completed","usage":{"input_tokens":8371,"output_tokens":417,"total_tokens":8788,"x_tools":{"image_search":{"count":1}}}}} ``` ## MCP ``` // 1-6. 推理阶段（与其他工具相同） // 7. response.mcp_call_arguments.delta - MCP 参数增量（MCP 独有事件） id:27 event:response.mcp_call_arguments.delta data:{"delta":"{\\"city\\": \\"北京\\"}","sequence_number":26,"output_index":1,"type":"response.mcp_call_arguments.delta","item_id":"msg_xxx"} // 8. response.mcp_call_arguments.done - MCP 参数完成（MCP 独有事件） id:28 event:response.mcp_call_arguments.done data:{"sequence_number":27,"arguments":"{\\"city\\": \\"北京\\"}","output_index":1,"type":"response.mcp_call_arguments.done","item_id":"msg_xxx"} // 9. response.output_item.added - MCP 工具调用开始（包含 name、server_label、arguments） id:29 event:response.output_item.added data:{"sequence_number":28,"item":{"name":"amap-maps-maps_weather","server_label":"MCP Server","arguments":"{\\"city\\": \\"北京\\"}","id":"msg_xxx","type":"mcp_call","status":"in_progress"},"output_index":1,"type":"response.output_item.added"} // 10. response.mcp_call.completed - MCP 调用完成（MCP 独有事件） id:30 event:response.mcp_call.completed data:{"sequence_number":29,"output_index":1,"type":"response.mcp_call.completed","item_id":"msg_xxx"} // 11. response.output_item.done - MCP 输出项完成（包含完整 output） id:31 event:response.output_item.done data:{"sequence_number":30,"item":{"output":"{\\"city\\":\\"北京市\\",\\"forecasts\\":[...]}","name":"amap-maps-maps_weather","server_label":"MCP Server","arguments":"{\\"city\\": \\"北京\\"}","id":"msg_xxx","type":"mcp_call","status":"completed"},"output_index":1,"type":"response.output_item.done"} // 12-15. 第二轮推理 + 最终消息输出 // 16. response.completed id:172 event:response.completed data:{"sequence_number":171,"type":"response.completed","response":{"output":[...],"status":"completed","usage":{"input_tokens":5019,"output_tokens":539,"total_tokens":5558}}} ``` ## 知识库搜索 ``` // 1-6. 推理阶段（与其他工具相同） // 7. response.output_item.added - 知识库搜索开始（包含 queries，无 results） id:19 event:response.output_item.added data:{"sequence_number":18,"item":{"id":"msg_xxx","type":"file_search_call","queries":["阿里云百炼X1手机","Alibaba Cloud Bailian X1 phone","百炼X1"],"status":"in_progress"},"output_index":1,"type":"response.output_item.added"} // 8. response.file_search_call.in_progress - 搜索进行中（file_search 独有事件） id:20 event:response.file_search_call.in_progress data:{"sequence_number":19,"output_index":1,"type":"response.file_search_call.in_progress","item_id":"msg_xxx"} // 9. response.file_search_call.searching - 正在搜索（file_search 独有事件） id:21 event:response.file_search_call.searching data:{"sequence_number":20,"output_index":1,"type":"response.file_search_call.searching","item_id":"msg_xxx"} // 10. response.file_search_call.completed - 搜索完成（file_search 独有事件） id:22 event:response.file_search_call.completed data:{"sequence_number":21,"output_index":1,"type":"response.file_search_call.completed","item_id":"msg_xxx"} // 11. response.output_item.done - 输出项完成（包含 queries + results） id:23 event:response.output_item.done data:{"sequence_number":22,"item":{"id":"msg_xxx","type":"file_search_call","queries":["阿里云百炼X1手机","Alibaba Cloud Bailian X1 phone","百炼X1"],"results":[{"score":0.7519,"filename":"阿里云百炼系列手机产品介绍","text":"阿里云百炼 X1 ——畅享极致视界...","file_id":"file_xxx"}],"status":"completed"},"output_index":1,"type":"response.output_item.done"} // 12-15. 第二轮推理 + 最终消息输出 // 16. response.completed id:146 event:response.completed data:{"sequence_number":145,"type":"response.completed","response":{"output":[...],"status":"completed","usage":{"input_tokens":1576,"output_tokens":722,"total_tokens":2298,"x_tools":{"file_search":{"count":1}}}}} ``` |
| --- | --- |
| 流式输出返回一系列 JSON 对象。每个对象包含 `type` 字段标识事件类型，`sequence_number` 字段标识事件顺序。`response.completed` 事件标志着流式传输的结束。 |
| **type** `*string*` 事件类型标识符。枚举值： - `response.created`：响应创建时触发，状态为 `queued`。 - `response.in_progress`：响应开始处理时触发，状态变为 `in_progress`。 - `response.output_item.added`：新的输出项（如 message、`web_extractor_call`）被添加到 output 数组时触发。当 `item.type` 为 `web_extractor_call` 时，表示网页抽取工具调用开始。 - `response.content_part.added`：输出项的 content 数组中新增内容块时触发。 - `response.output_text.delta`：增量文本生成时触发，多次触发，`delta` 字段包含新增文本片段。 - `response.output_text.done`：文本生成完成时触发，`text` 字段包含完整文本。 - `response.content_part.done`：内容块完成时触发，`part` 对象包含完整内容块。 - `response.output_item.done`：输出项生成完成时触发，`item` 对象包含完整输出项。当 `item.type` 为 `web_extractor_call` 时，表示网页抽取工具调用完成。 - `response.reasoning_summary_text.delta`：（开启思考模式时）推理摘要增量文本，`delta` 字段包含新增摘要片段。 - `response.reasoning_summary_text.done`：（开启思考模式时）推理摘要完成，`text` 字段包含完整摘要。 - `response.web_search_call.in_progress` / `searching` / `completed`：（使用 web\\_search 工具时）搜索状态变化事件。 - `response.code_interpreter_call.in_progress` / `interpreting` / `completed`：（使用 code\\_interpreter 工具时）代码执行状态变化事件。 - **注意：**使用 `web_extractor` 工具时，没有专门的事件类型标识符。网页抽取工具调用通过通用的 `response.output_item.added` 和 `response.output_item.done` 事件传递，通过 `item.type` 字段（值为 `web_extractor_call`）来识别。 - `response.mcp_call_arguments.delta` / `response.mcp_call_arguments.done`：（使用 mcp 工具时）MCP 调用参数的增量和完成事件。 - `response.mcp_call.completed`：（使用 mcp 工具时）MCP 服务调用完成。 - `response.file_search_call.in_progress` / `searching` / `completed`：（使用 file\\_search 工具时）知识库搜索状态变化事件。 - **注意：**使用 `web_search_image` 和 `image_search` 工具时，没有专门的中间状态事件。工具调用通过 `response.output_item.added`（调用开始）和 `response.output_item.done`（调用完成）事件传递。 - `response.completed`：响应生成完成时触发，`response` 对象包含完整响应（含 usage）。此事件标志流式传输结束。 |
| **sequence\\_number** `*integer*` 事件序列号，从 0 开始递增。用于确保客户端按正确顺序处理事件。 |
| **response** `*object*` 响应对象。出现在 `response.created`、`response.in_progress` 和 `response.completed` 事件中。在 `response.completed` 事件中包含完整的响应数据（包括 `output` 和 `usage`），其结构与非流式响应的 Response 对象一致。 |
| **item** `*object*` 输出项对象。出现在 `response.output_item.added` 和 `response.output_item.done` 事件中。在 `added` 事件中为初始骨架（content 为空数组），在 `done` 事件中为完整对象。 **属性** **id** `*string*` 输出项的唯一标识符（如 `msg_xxx`）。 **type** `*string*` 输出项类型。枚举值：`message`（消息）、`reasoning`（推理）、`web_search_call`（搜索）、`web_search_image_call`（文搜图）、`image_search_call`（图搜图）、`mcp_call`（MCP 调用）、`file_search_call`（知识库搜索）。 **role** `*string*` 消息角色，固定为 `assistant`。仅当 type 为 `message` 时存在。 **status** `*string*` 生成状态。在 `added` 事件中为 `in_progress`，在 `done` 事件中为 `completed`。 **content** `*array*` 消息内容数组。在 `added` 事件中为空数组 `[]`，在 `done` 事件中包含完整的内容块对象（结构与 `part` 对象相同）。 |
| **part** `*object*` 内容块对象。出现在 `response.content_part.added` 和 `response.content_part.done` 事件中。 **属性** **type** `*string*` 内容块类型，固定为 `output_text`。 **text** `*string*` 文本内容。在 `added` 事件中为空字符串，在 `done` 事件中为完整文本。 **annotations** `*array*` 文本注释数组。通常为空数组。 **logprobs** `*object \\| null*` Token 的对数概率信息。当前固定返回 `null`。 |
| **delta** `*string*` 增量文本内容。出现在 `response.output_text.delta` 事件中，包含本次新增的文本片段。客户端应将所有 `delta` 拼接以获得完整文本。 |
| **text** `*string*` 完整文本内容。出现在 `response.output_text.done` 事件中，包含该内容块的完整文本，可用于校验 `delta` 拼接结果。 |
| **item\\_id** `*string*` 输出项的唯一标识符。用于关联同一输出项的相关事件。 |
| **output\\_index** `*integer*` 输出项在 `output` 数组中的索引位置。 |
| **content\\_index** `*integer*` 内容块在 `content` 数组中的索引位置。 |
| **summary\\_index** `*integer*` 摘要数组索引。出现在 `response.reasoning_summary_text.delta` 和 `response.reasoning_summary_text.done` 事件中。 |

## **常见问题**

**Q：如何传递多轮对话的上下文？**

A：在发起新一轮对话请求时，请将上一轮模型响应成功返回的`id`作为 `previous_response_id` 参数传入。

**Q：为什么响应示例中的某些字段未在本文说明？**

A：如果使用OpenAI的官方SDK，它可能会根据其自身的模型结构输出一些额外的字段（通常为`null`）。这些字段是OpenAI协议本身定义的，我们的服务当前不支持，所以它们为空值。只需关注本文档中描述的字段即可。

.aliyun-docs-content .one-codeblocks pre { max-height: calc(80vh - 136px) !important; height: auto; } .tab-item { font-size: 12px !important; /\* 你可以根据需要调整字体大小 \*/ padding: 0px 5px !important; } .expandable-content { border-left: none !important; border-right: none !important; border-bottom: none !important; } .one-codeblocks.stick-top.section { overflow: hidden !important; }

.table-wrapper { overflow: visible !important; } /\* 调整 table 宽度 \*/ .aliyun-docs-content table.medium-width { max-width: 1018px; width: 100%; } .aliyun-docs-content table.table-no-border tr td:first-child { padding-left: 0; } .aliyun-docs-content table.table-no-border tr td:last-child { padding-right: 0; } /\* 支持吸顶 \*/ div:has(.aliyun-docs-content), .aliyun-docs-content .markdown-body { overflow: visible; } .stick-top { position: sticky; top: 46px; } /\*\*代码块字体\*\*/ /\* 减少表格中的代码块 margin，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body table .help-code-block { margin: 0 !important; } /\* 减少表格中的代码块字号，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body .help-code-block pre { font-size: 12px !important; } /\* 减少表格中的代码块字号，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body .help-code-block pre code { font-size: 12px !important; } /\*\* API Reference 表格 \*\*/ .aliyun-docs-content table.api-reference tr td:first-child { margin: 0px; border-bottom: 1px solid #d8d8d8; } .aliyun-docs-content table.api-reference tr:last-child td:first-child { border-bottom: none; } .aliyun-docs-content table.api-reference p { color: #6e6e80; } .aliyun-docs-content table.api-reference b, i { color: #181818; } .aliyun-docs-content table.api-reference .collapse { border: none; margin-top: 4px; margin-bottom: 4px; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold { padding: 0; } .aliyun-docs-content table.api-reference .collapse .expandable-title { padding: 0; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold .title { margin-left: 16px; } .aliyun-docs-content table.api-reference .collapse .expandable-title .title { margin-left: 16px; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold i.icon { position: absolute; color: #777; font-weight: 100; } .aliyun-docs-content table.api-reference .collapse .expandable-title i.icon { position: absolute; color: #777; font-weight: 100; } .aliyun-docs-content table.api-reference .collapse.expanded .expandable-content { padding: 10px 14px 10px 14px !important; margin: 0; border: 1px solid #e9e9e9; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold b { font-size: 13px; font-weight: normal; color: #6e6e80; } .aliyun-docs-content table.api-reference .collapse .expandable-title b { font-size: 13px; font-weight: normal; color: #6e6e80; } .aliyun-docs-content table.api-reference .tabbed-content-box { border: none; } .aliyun-docs-content table.api-reference .tabbed-content-box section { padding: 8px 0 !important; } .aliyun-docs-content table.api-reference .tabbed-content-box.mini .tab-box { /\* position: absolute; left: 40px; right: 0; \*/ } .aliyun-docs-content .margin-top-33 { margin-top: 33px !important; } .aliyun-docs-content .two-codeblocks pre { max-height: calc(50vh - 136px) !important; height: auto; } .expandable-content section { border-bottom: 1px solid #e9e9e9; padding-top: 6px; padding-bottom: 4px; } .expandable-content section:last-child { border-bottom: none; } .expandable-content section:first-child { padding-top: 0; }

千问-文生图模型（Qwen-Image）是一款通用图像生成模型，支持多种艺术风格，尤其擅长**复杂文本渲染**。模型支持多行布局、段落级文本生成以及细粒度细节刻画，可实现复杂的图文混合布局设计。

| **快速入口：**[使用指南](https://help.aliyun.com/zh/model-studio/text-to-image) **\\|** 在线体验（[北京](https://bailian.console.aliyun.com/cn-beijing/?tab=model#/efm/model_experience_center/vision?currentTab=imageGenerate&modelId=qwen-image-max) \\| [新加坡](https://modelstudio.console.aliyun.com/ap-southeast-1?tab=dashboard#/efm/model_experience_center/vision?currentTab=imageGenerate&modelId=qwen-image-max)） **\\|** [技术博客](https://qwen.ai/blog?id=9467b4bff9c638e847f08443802c6b96ab116a87&from=research.research-list) |
| --- | --- | --- | --- |

## **效果展示**

| **输入提示词** | **输出图像** |
| --- | --- |
| 冬日北京的都市街景，青灰瓦顶、朱红色外墙的两间相邻中式商铺比肩而立，檐下悬挂印有剪纸马的暖光灯笼，在阴天漫射光中投下柔和光晕，映照湿润鹅卵石路面泛起细腻反光。左侧为书法店：靛蓝色老旧的牌匾上以遒劲行书刻着“文字渲染”。店门口的玻璃上挂着一幅字，自上而下，用田英章硬笔写着“专业幻灯片 中英文海报 高级信息图”，落款印章为“1k token”朱砂印。店内的墙上，可以模糊的辨认有三幅竖排的书法作品，第一幅写着“阿里巴巴”，第二幅写着“通义千问”，第三幅写着“图像生成”。一位白发苍苍的老人背对着镜头观赏。右侧为花店，牌匾上以鲜花做成文字“真实质感”；店内多层花架陈列红玫瑰、粉洋牡丹和绿植，门上贴了一个圆形花边标识，标识上写着“2k resolution”，门口摆放了一个彩色霓虹灯，上面写着“细腻刻画 人物 自然 建筑”。两家店中间堆放了一个雪人，举了一老式小黑板，上面用粉笔字写着“Qwen-Image-2.0 正式发布”。街道左侧，年轻情侣依偎在一起，女孩是瘦脸，身穿米白色羊绒大衣，肉色光腿神器。女孩举着心形透明气球，气球印有白色的字：“生图编辑二合一”。里面有一个毛茸茸的卡皮巴拉玩偶。男孩身着剪裁合体的深灰色呢子外套，内搭浅色高领毛衣。街道右侧，一个后背上写着“更小模型，更快速度”的骑手疾驰而过。整条街光影交织、动静相宜。 | ![image (10)](https://help-static-aliyun-doc.aliyuncs.com/assets/img/zh-CN/9373463771/p1058343.png) |

## 模型概览

| **模型名称** | **模型简介** | **输出图像规格** |
| --- | --- | --- |
| qwen-image-2.0-pro `**推荐**` > 当前与qwen-image-2.0-pro-2026-03-03能力相同 | 千问图像生成与编辑模型Pro系列。文字渲染、真实质感、语义遵循能力更强。 > 图像编辑请参考[千问-图像编辑](https://help.aliyun.com/zh/model-studio/qwen-image-edit-api)。 | 图像分辨率：支持自由设置宽高，输出图像总像素需在512\\*512至2048\\*2048之间。默认分辨率为2048\\*2048。 图像格式：png 图像张数：1-6张 |
| qwen-image-2.0-pro-2026-04-22 `**推荐**` |
| qwen-image-2.0-pro-2026-03-03 |
| qwen-image-2.0 `**推荐**` > 当前与qwen-image-2.0-2026-03-03能力相同 | 千问图像生成与编辑模型加速版，兼顾效果与响应速度。 > 图像编辑请参考[千问-图像编辑](https://help.aliyun.com/zh/model-studio/qwen-image-edit-api)。 |
| qwen-image-2.0-2026-03-03 `**推荐**` |
| qwen-image-max > 当前与qwen-image-max-2025-12-30能力相同 | 千问图像生成模型Max系列。真实感、自然度更强，AI合成痕迹更低。 | 图像分辨率：可选分辨率及对应宽高比例请参见[size参数设置](#1c7b41f2d13sv) 图像格式：png 图像张数：固定1张 |
| qwen-image-max-2025-12-30 |
| qwen-image-plus > 当前与qwen-image能力相同 | 千问图像生成模型Plus系列，擅长多样化艺术风格与文字渲染。 |
| qwen-image-plus-2026-01-09 |
| qwen-image |

各地域支持的模型请参见[百炼控制台](https://bailian.console.aliyun.com/cn-beijing?tab=model#/model-market/all)。

## 前提条件

在调用前，先[获取API Key](https://help.aliyun.com/zh/model-studio/get-api-key)，再[配置API Key到环境变量](https://help.aliyun.com/zh/model-studio/configure-api-key-through-environment-variables)。如需通过SDK进行调用，请[安装DashScope SDK](https://help.aliyun.com/zh/model-studio/install-sdk)。

**重要**

北京和新加坡地域拥有独立的 **API Key** 与**请求地址**，不可混用，跨地域调用将导致鉴权失败或服务报错。

## **同步接口（推荐）**

### **HTTP调用**

千问图像模型支持同步接口，一次请求即可获得结果，调用流程简单，推荐用于多数场景。

**北京地域**：`POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`

**新加坡地域**：`POST https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`

| #### 请求参数 | ## **文生图** ``` curl --location 'https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation' \\ --header 'Content-Type: application/json' \\ --header "Authorization: Bearer $DASHSCOPE_API_KEY" \\ --data '{ "model": "qwen-image-2.0-pro", "input": { "messages": [ { "role": "user", "content": [ { "text": "冬日北京的都市街景，青灰瓦顶、朱红色外墙的两间相邻中式商铺比肩而立，檐下悬挂印有剪纸马的暖光灯笼，在阴天漫射光中投下柔和光晕，映照湿润鹅卵石路面泛起细腻反光。左侧为书法店：靛蓝色老旧的牌匾上以遒劲行书刻着“文字渲染”。店门口的玻璃上挂着一幅字，自上而下，用田英章硬笔写着“专业幻灯片 中英文海报 高级信息图”，落款印章为“1k token”朱砂印。店内的墙上，可以模糊的辨认有三幅竖排的书法作品，第一幅写着“阿里巴巴”，第二幅写着“通义千问”，第三幅写着“图像生成”。一位白发苍苍的老人背对着镜头观赏。右侧为花店，牌匾上以鲜花做成文字“真实质感”；店内多层花架陈列红玫瑰、粉洋牡丹和绿植，门上贴了一个圆形花边标识，标识上写着“2k resolution”，门口摆放了一个彩色霓虹灯，上面写着“细腻刻画 人物 自然 建筑”。两家店中间堆放了一个雪人，举了一老式小黑板，上面用粉笔字写着“Qwen-Image-2.0 正式发布”。街道左侧，年轻情侣依偎在一起，女孩是瘦脸，身穿米白色羊绒大衣，肉色光腿神器。女孩举着心形透明气球，气球印有白色的字：“生图编辑二合一”。里面有一个毛茸茸的卡皮巴拉玩偶。男孩身着剪裁合体的深灰色呢子外套，内搭浅色高领毛衣。街道右侧，一个后背上写着“更小模型，更快速度”的骑手疾驰而过。整条街光影交织、动静相宜。" } ] } ] }, "parameters": { "negative_prompt": "低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，画面具有AI感。构图混乱。文字模糊，扭曲。", "prompt_extend": true, "watermark": false, "size": "2048*2048" } }' ``` |
| --- | --- |
| ##### 请求头（Headers） |
| **Content-Type** `*string*` **（必选）** 请求内容类型。此参数必须设置为`application/json`。 |
| **Authorization** `*string*`**（必选）** 请求身份认证。接口使用阿里云百炼API-Key进行身份认证。示例值：Bearer sk-xxxx。 |
| ##### 请求体（Request Body） |
| **model** `*string*` **（必选）** 模型名称。示例值：`qwen-image-2.0-pro`。 |
| **input** `*object*` **（必选）** 输入的基本信息。 **属性** **messages** `*array*` **（必选）** 请求内容数组。**当前仅支持单轮对话**，数组内**有且只有一个元素**。 **属性** **role** `*string*` **（必选）** 消息的角色。此参数必须设置为`user`。 **content** `*array*` **（必选）** 消息内容数组。 **属性** **text** `*string*` **（必选）** 正向提示词用于描述您期望生成的图像内容、风格和构图。 支持中英文，长度不超过800个字符，每个汉字、字母、数字或符号计为一个字符，超过部分会自动截断。 示例值：一只坐着的橘黄色的猫，表情愉悦，活泼可爱，逼真准确。 **注意**：仅支持传入一个text，不传或传入多个将报错。 |
| **parameters** `*object*` （可选） 图像处理参数。 **属性** **negative\\_prompt** `*string*` （可选） 反向提示词，用于描述不希望在图像中出现的内容，对画面进行限制。 支持中英文，长度不超过500个字符，超出部分将自动截断。 示例值：低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，画面具有AI感。构图混乱。文字模糊，扭曲。 **size** `*string*` （可选） 输出图像的分辨率，格式为`宽*高`。 **qwen-image-2.0系列模型**：输出图像总像素需在`512*512`至`2048*2048`之间，默认分辨率为`2048*2048`。推荐分辨率： - `2688*1536` ：16:9 - `1536*2688` ：9:16 - `2048*2048`（**默认值）**：1:1 - `2368*1728` ：4:3 - `1728*2368` ：3:4 **qwen-image-max、qwen-image-plus系列模型**：默认分辨率为`1664*928`。**可选**的分辨率及其对应的图像宽高比例为： - `1664*928`（**默认值**）：16:9 - `1472*1104`：4:3 - `1328*1328`：1:1 - `1104*1472`：3:4 - `928*1664`：9:16 **n** `*integer*` （可选） 输出图像的数量，默认值为1。 对于qwen-image-2.0系列模型，可选择输出1-6张图片。 对于qwen-image-max、qwen-image-plus系列模型，此参数固定为1，设置其他值将导致报错。 **prompt\\_extend** `*bool*` （可选） 是否开启 Prompt（提示词）智能改写功能。开启后模型将对正向提示词进行优化与润色。此功能不会修改反向提示词。 - `true`：**默认值**，开启智能改写。如果希望图像内容更多样化，由模型补充细节，建议开启此选项。 - `false`：关闭智能改写。如果图像细节更可控，建议关闭此选项，并参考[文生图Prompt指南](https://help.aliyun.com/zh/model-studio/text-to-image-prompt)进行优化， 点击查看改写示例 > 当前仅异步接口返回实际提示词。 **原始提示词（orig\\_prompt）**：一只坐着的橘黄色的猫，表情愉悦，活泼可爱，逼真准确。 **实际提示词（actual\\_prompt）**：一只坐着的橘黄色猫咪，毛发蓬松柔软，阳光透过窗户洒在它身上，呈现出温暖的光泽。猫咪体型匀称，四肢自然弯曲，稳稳地坐在木质地板上，尾巴轻轻卷曲在身侧，显得格外放松而优雅。它的大眼睛圆润明亮，瞳孔微微收缩，流露出愉悦而灵动的神情，嘴角微扬，仿佛正享受着美好的时光。耳朵微微向前倾斜，透露出活泼与好奇。背景是一间温馨的现代家居客厅，浅色木地板、一扇半开的窗户透进柔和的自然光，窗外可见绿意盎然的庭院，窗台上摆放着几盆绿植。画面采用真实摄影风格，细节逼真，光影层次丰富，突出猫咪的毛发质感、眼神神态与整体姿态的生动自然，整体氛围轻松愉快，充满生活气息。 **watermark** `*bool*` （可选） 是否在图像右下角添加 "Qwen-Image" 水印。默认值为 `false`。水印样式：![1](https://help-static-aliyun-doc.aliyuncs.com/assets/img/zh-CN/8972029571/p1012089.jpg) **seed** `*integer*` （可选） 随机数种子，取值范围`[0,2147483647]`。 使用相同的`seed`参数值可使生成内容保持相对稳定。若不提供，算法将自动使用随机数种子。 **注意**：模型生成过程具有概率性，即使使用相同的`seed`，也不能保证每次生成结果完全一致。 |

| #### 响应参数 | ## 任务执行成功 图像URL仅保留24小时，超时后会被自动清除，请及时保存生成的图像。 ``` { "output": { "choices": [ { "finish_reason": "stop", "message": { "content": [ { "image": "https://dashscope-result-sh.oss-cn-shanghai.aliyuncs.com/xxx.png?Expires=xxx" } ], "role": "assistant" } } ] }, "usage": { "height": 2048, "image_count": 1, "width": 2048 }, "request_id": "d0250a3d-b07f-49e1-bdc8-6793f4929xxx" } ``` ## 任务执行异常 如果因为某种原因导致任务执行失败，将返回相关信息，可以通过code和message字段明确指示错误原因。请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)进行解决。 ``` { "request_id": "a4d78a5f-655f-9639-8437-xxxxxx", "code": "InvalidParameter", "message": "num_images_per_prompt must be 1" } ``` |
| --- | --- |
| **output** `*object*` 任务输出信息。 **属性** **choices** `*array*` 模型生成的输出内容。此数组仅包含一个元素。 **属性** **finish\\_reason** `*string*` 任务停止原因，自然停止时为`stop`。 **message** `*object*` 模型返回的消息。 **属性** **role** `*string*` 消息的角色，固定为`assistant`。 **content** `*array*` **属性** **image** `*string*` 生成图像的 URL，图像格式为PNG。**链接有效期为24小时**，请及时下载并保存图像。 **task\\_metric** `*object*` 任务结果统计。使用qwen-image-2.0系列时无此返回值。 **属性** **TOTAL** `*integer*` 总的任务数。 **SUCCEEDED** `*integer*` 任务状态为成功的任务数。 **FAILED** `*integer*` 任务状态为失败的任务数。 |
| **usage** `*object*` 输出信息统计。只对成功的结果计数。 **属性** **image\\_count** `*integer*` 模型生成图像的数量，当前固定为1。 **width** `*integer*` 模型生成图像的宽度（像素）。 **height** `*integer*` 模型生成图像的高度（像素）。 |
| **request\\_id** `*string*` 请求唯一标识。可用于请求明细溯源和问题排查。 |
| **code** `*string*` 请求失败的错误码。请求成功时不会返回此参数，详情请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)。 |
| **message** `*string*` 请求失败的详细信息。请求成功时不会返回此参数，详情请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)。 |

* * *

### **DashScope SDK调用**

DashScope SDK目前已支持Python和Java。

SDK与HTTP接口的参数名基本一致，参数结构根据语言特性进行封装。同步调用参数说明可参考[HTTP调用](#90575c8228nmq)。

## Python

**说明**

请先确认已安装最新版DashScope Python SDK，否则可能运行报错：[安装SDK](https://help.aliyun.com/zh/model-studio/install-sdk)。

##### **请求示例**

```
import json
import os
import dashscope
from dashscope import MultiModalConversation

# 以下为北京地域url，若使用新加坡地域的模型，需将url替换为：https://dashscope-intl.aliyuncs.com/api/v1
dashscope.base_http_api_url = 'https://dashscope.aliyuncs.com/api/v1'

messages = [
    {
        "role": "user",
        "content": [
            {"text": "冬日北京的都市街景，青灰瓦顶、朱红色外墙的两间相邻中式商铺比肩而立，檐下悬挂印有剪纸马的暖光灯笼，在阴天漫射光中投下柔和光晕，映照湿润鹅卵石路面泛起细腻反光。左侧为书法店：靛蓝色老旧的牌匾上以遒劲行书刻着“文字渲染”。店门口的玻璃上挂着一幅字，自上而下，用田英章硬笔写着“专业幻灯片 中英文海报 高级信息图”，落款印章为“1k token”朱砂印。店内的墙上，可以模糊的辨认有三幅竖排的书法作品，第一幅写着“阿里巴巴”，第二幅写着“通义千问”，第三幅写着“图像生成”。一位白发苍苍的老人背对着镜头观赏。右侧为花店，牌匾上以鲜花做成文字“真实质感”；店内多层花架陈列红玫瑰、粉洋牡丹和绿植，门上贴了一个圆形花边标识，标识上写着“2k resolution”，门口摆放了一个彩色霓虹灯，上面写着“细腻刻画 人物 自然 建筑”。两家店中间堆放了一个雪人，举了一老式小黑板，上面用粉笔字写着“Qwen-Image-2.0 正式发布”。街道左侧，年轻情侣依偎在一起，女孩是瘦脸，身穿米白色羊绒大衣，肉色光腿神器。女孩举着心形透明气球，气球印有白色的字：“生图编辑二合一”。里面有一个毛茸茸的卡皮巴拉玩偶。男孩身着剪裁合体的深灰色呢子外套，内搭浅色高领毛衣。街道右侧，一个后背上写着“更小模型，更快速度”的骑手疾驰而过。整条街光影交织、动静相宜。"}
        ]
    }
]

# 新加坡和北京地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
# 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx"
api_key = os.getenv("DASHSCOPE_API_KEY")

response = MultiModalConversation.call(
    api_key=api_key,
    model="qwen-image-2.0-pro",
    messages=messages,
    result_format='message',
    stream=False,
    watermark=False,
    prompt_extend=True,
    negative_prompt="低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，画面具有AI感。构图混乱。文字模糊，扭曲。",
    size='2048*2048'
)

if response.status_code == 200:
    print(json.dumps(response, ensure_ascii=False))
else:
    print(f"HTTP返回码：{response.status_code}")
    print(f"错误码：{response.code}")
    print(f"错误信息：{response.message}")
    print("请参考文档：https://help.aliyun.com/zh/model-studio/developer-reference/error-code")
```

##### **响应示例**

> 图像链接的有效期为24小时，请及时下载图像。

```
{
    "status_code": 200,
    "request_id": "d2d1a8c0-325f-9b9d-8b90-xxxxxx",
    "code": "",
    "message": "",
    "output": {
        "text": null,
        "finish_reason": null,
        "choices": [
            {
                "finish_reason": "stop",
                "message": {
                    "role": "assistant",
                    "content": [
                        {
                            "image": "https://dashscope-result-wlcb.oss-cn-wulanchabu.aliyuncs.com/xxx.png?Expires=xxx"
                        }
                    ]
                }
            }
        ]
    },
    "usage": {
        "input_tokens": 0,
        "output_tokens": 0,
        "width": 2048,
        "image_count": 1,
        "height": 2048
    }
}
```

## Java

**说明**

请先确认已安装最新版DashScope Java SDK，否则可能运行报错：[安装SDK](https://help.aliyun.com/zh/model-studio/install-sdk)。

##### **请求示例**

```
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.utils.JsonUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class QwenImage {

    static {
        // 以下为北京地域url，若使用新加坡地域的模型，需将url替换为：https://dashscope-intl.aliyuncs.com/api/v1
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
    }

    // 新加坡和北京地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
    // 若没有配置环境变量，请用百炼API Key将下行替换为：static String apiKey ="sk-xxx"
    static String apiKey = System.getenv("DASHSCOPE_API_KEY");

    public static void call() throws ApiException, NoApiKeyException, UploadFileException, IOException {

        MultiModalConversation conv = new MultiModalConversation();

        MultiModalMessage userMessage = MultiModalMessage.builder().role(Role.USER.getValue())
                .content(Arrays.asList(
                        Collections.singletonMap("text", "冬日北京的都市街景，青灰瓦顶、朱红色外墙的两间相邻中式商铺比肩而立，檐下悬挂印有剪纸马的暖光灯笼，在阴天漫射光中投下柔和光晕，映照湿润鹅卵石路面泛起细腻反光。左侧为书法店：靛蓝色老旧的牌匾上以遒劲行书刻着“文字渲染”。店门口的玻璃上挂着一幅字，自上而下，用田英章硬笔写着“专业幻灯片 中英文海报 高级信息图”，落款印章为“1k token”朱砂印。店内的墙上，可以模糊的辨认有三幅竖排的书法作品，第一幅写着“阿里巴巴”，第二幅写着“通义千问”，第三幅写着“图像生成”。一位白发苍苍的老人背对着镜头观赏。右侧为花店，牌匾上以鲜花做成文字“真实质感”；店内多层花架陈列红玫瑰、粉洋牡丹和绿植，门上贴了一个圆形花边标识，标识上写着“2k resolution”，门口摆放了一个彩色霓虹灯，上面写着“细腻刻画 人物 自然 建筑”。两家店中间堆放了一个雪人，举了一老式小黑板，上面用粉笔字写着“Qwen-Image-2.0 正式发布”。街道左侧，年轻情侣依偎在一起，女孩是瘦脸，身穿米白色羊绒大衣，肉色光腿神器。女孩举着心形透明气球，气球印有白色的字：“生图编辑二合一”。里面有一个毛茸茸的卡皮巴拉玩偶。男孩身着剪裁合体的深灰色呢子外套，内搭浅色高领毛衣。街道右侧，一个后背上写着“更小模型，更快速度”的骑手疾驰而过。整条街光影交织、动静相宜。")
                )).build();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("watermark", false);
        parameters.put("prompt_extend", true);
        parameters.put("negative_prompt", "低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，画面具有AI感。构图混乱。文字模糊，扭曲。");
        parameters.put("size", "2048*2048");

        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(apiKey)
                .model("qwen-image-2.0-pro")
                .messages(Collections.singletonList(userMessage))
                .parameters(parameters)
                .build();

        MultiModalConversationResult result = conv.call(param);
        System.out.println(JsonUtils.toJson(result));
    }

    public static void main(String[] args) {
        try {
            call();
        } catch (ApiException | NoApiKeyException | UploadFileException | IOException e) {
            System.out.println(e.getMessage());
        }
        System.exit(0);
    }
}
```

##### **响应示例**

> 图像链接的有效期为24小时，请及时下载图像。

```
{
    "requestId": "5b6f2d04-b019-40db-a5cc-xxxxxx",
    "usage": {
        "image_count": 1,
        "width": 2048,
        "height": 2048
    },
    "output": {
        "choices": [
            {
                "finish_reason": "stop",
                "message": {
                    "role": "assistant",
                    "content": [
                        {
                            "image": "https://dashscope-result-wlcb.oss-cn-wulanchabu.aliyuncs.com/xxx.png?Expires=xxx"
                        }
                    ]
                }
            }
        ]
    }
}
```

## **异步接口**

**重要**

当前仅qwen-image-plus、qwen-image模型支持异步接口调用。

### **HTTP调用**

调用流程分为两步：

1.  **创建任务获取任务ID**：发送一个请求创建任务，该请求会返回**任务ID（task\_id）**。

2.  **根据任务ID查询结果**：使用task\_id轮询任务状态，直到任务完成并获得图像URL。


#### **步骤1：创建任务获取任务ID**

**北京地域**：`POST https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis`

**新加坡地域**：`POST https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis`

**说明**

-   创建成功后，使用接口返回的 `task_id` 查询结果，task\_id 有效期为 24 小时。**请勿重复创建任务**，轮询获取即可。

-   新手指引请参见[Postman](https://help.aliyun.com/zh/model-studio/first-call-to-image-and-video-api)。


| ##### **请求参数** | ## 文生图 当前仅`qwen-image-plus`、`qwen-image`模型支持异步接口调用。 ``` curl -X POST https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis \\ -H 'X-DashScope-Async: enable' \\ -H "Authorization: Bearer $DASHSCOPE_API_KEY" \\ -H 'Content-Type: application/json' \\ -d '{ "model": "qwen-image-plus", "input": { "prompt": "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。" }, "parameters": { "negative_prompt":" ", "size": "1664*928", "n": 1, "prompt_extend": true, "watermark": false } }' ``` |
| --- | --- |
| ###### **请求头（Headers）** |
| **Content-Type** `*string*` **（必选）** 请求内容类型。此参数必须设置为`application/json`。 |
| **Authorization** `*string*`**（必选）** 请求身份认证。接口使用阿里云百炼API-Key进行身份认证。示例值：Bearer sk-xxxx。 |
| **X-DashScope-Async** `*string*` **（必选）** 异步处理配置参数。HTTP请求只支持异步，**必须设置为**`**enable**`。 **重要** 缺少此请求头将报错：“current user api does not support synchronous calls”。 |
| ###### **请求体（Request Body）** |
| **model** `*string*` **（必选）** 模型名称。当前仅`qwen-image-plus`、`qwen-image`模型支持异步接口调用。 示例值：`qwen-image-plus`。 |
| **input** `*object*` **（必选）** 输入的基本信息，如提示词等。 **属性** **prompt** `*string*` **（必选）** 正向提示词，用来描述生成图像中期望包含的元素和视觉特点。 支持中英文，长度不超过800个字符，每个汉字、字母、数字或符号计为一个字符，超出部分将自动截断。 示例值：一只坐着的橘黄色的猫，表情愉悦，活泼可爱，逼真准确。 **negative\\_prompt** `*string*` （可选） 反向提示词，用于描述不希望在图像中出现的内容，对画面进行限制。 支持中英文，长度不超过500个字符，超出部分将自动截断。 示例值：低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，过度光滑，画面具有AI感。构图混乱。文字模糊，扭曲。 |
| **parameters** `*object*` （可选） 图像处理参数。 **属性** **size** `*string*` （可选） 输出图像的分辨率，格式为`宽*高`。 **qwen-image-2.0系列模型**：输出图像总像素需在`512*512`至`2048*2048`之间，默认分辨率为`2048*2048`。推荐分辨率： - `2688*1536` ：16:9 - `1536*2688` ：9:16 - `2048*2048`（**默认值）**：1:1 - `2368*1728` ：4:3 - `1728*2368` ：3:4 **qwen-image-max、qwen-image-plus系列模型**：默认分辨率为`1664*928`。**可选**的分辨率及其对应的图像宽高比例为： - `1664*928`（**默认值**）：16:9 - `1472*1104`：4:3 - `1328*1328`：1:1 - `1104*1472`：3:4 - `928*1664`：9:16 **n** `*integer*` （可选） 生成图像的数量。**此参数当前固定为1，设置其他值将导致报错。** **prompt\\_extend** `*bool*` （可选） 是否开启 Prompt（提示词）智能改写功能。开启后模型将对正向提示词进行优化与润色。此功能不会修改反向提示词。 - `true`：**默认值**，开启智能改写。如果希望图像内容更多样化，由模型补充细节，建议开启此选项。 - `false`：关闭智能改写。如果图像细节更可控，建议关闭此选项，并参考[文生图Prompt指南](https://help.aliyun.com/zh/model-studio/text-to-image-prompt)进行优化， 点击查看改写示例 > 当前仅异步接口返回实际提示词。 **原始提示词（orig\\_prompt）**：一只坐着的橘黄色的猫，表情愉悦，活泼可爱，逼真准确。 **实际提示词（actual\\_prompt）**：一只坐着的橘黄色猫咪，毛发蓬松柔软，阳光透过窗户洒在它身上，呈现出温暖的光泽。猫咪体型匀称，四肢自然弯曲，稳稳地坐在木质地板上，尾巴轻轻卷曲在身侧，显得格外放松而优雅。它的大眼睛圆润明亮，瞳孔微微收缩，流露出愉悦而灵动的神情，嘴角微扬，仿佛正享受着美好的时光。耳朵微微向前倾斜，透露出活泼与好奇。背景是一间温馨的现代家居客厅，浅色木地板、一扇半开的窗户透进柔和的自然光，窗外可见绿意盎然的庭院，窗台上摆放着几盆绿植。画面采用真实摄影风格，细节逼真，光影层次丰富，突出猫咪的毛发质感、眼神神态与整体姿态的生动自然，整体氛围轻松愉快，充满生活气息。 **watermark** `*bool*` （可选） 是否在图像右下角添加 "Qwen-Image" 水印。默认值为 `false`。水印样式：![1](https://help-static-aliyun-doc.aliyuncs.com/assets/img/zh-CN/8972029571/p1012089.jpg) **seed** `*integer*` （可选） 随机数种子，取值范围`[0,2147483647]`。 使用相同的`seed`参数值可使生成内容保持相对稳定。若不提供，算法将自动使用随机数种子。 **注意**：模型生成过程具有概率性，即使使用相同的`seed`，也不能保证每次生成结果完全一致。 |

| ##### **响应参数** | #### 成功响应 请保存 task\\_id，用于查询任务状态与结果。 ``` { "output": { "task_status": "PENDING", "task_id": "0385dc79-5ff8-4d82-bcb6-xxxxxx" }, "request_id": "4909100c-7b5a-9f92-bfe5-xxxxxx" } ``` #### 异常响应 创建任务失败，请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)进行解决。 ``` { "code": "InvalidApiKey", "message": "No API-key provided.", "request_id": "7438d53d-6eb8-4596-8835-xxxxxx" } ``` |
| --- | --- |
| **output** `*object*` 任务输出信息。 **属性** **task\\_id** `*string*` 任务ID。查询有效期24小时。 **task\\_status** `*string*` 任务状态。 **枚举值** - PENDING：任务排队中 - RUNNING：任务处理中 - SUCCEEDED：任务执行成功 - FAILED：任务执行失败 - CANCELED：任务已取消 - UNKNOWN：任务不存在或状态未知 |
| **request\\_id** `*string*` 请求唯一标识。可用于请求明细溯源和问题排查。 |
| **code** `*string*` 请求失败的错误码。请求成功时不会返回此参数，详情请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)。 |
| **message** `*string*` 请求失败的详细信息。请求成功时不会返回此参数，详情请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)。 |

#### **步骤2：根据任务ID查询结果**

##### **北京**

`GET https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}`

##### 新加坡

`GET https://dashscope-intl.aliyuncs.com/api/v1/tasks/{task_id}`

**说明**

-   **轮询建议**：图像生成过程耗时较长，建议采用**轮询**机制，并设置合理的查询间隔（如 10 秒）来获取结果。

-   **任务状态流转**：PENDING（排队中）→ RUNNING（处理中）→ SUCCEEDED（成功）/ FAILED（失败）。

-   **结果链接**：任务成功后返回图像链接，有效期为 **24 小时**。建议在获取链接后立即下载并转存至永久存储（如[阿里云 OSS](https://help.aliyun.com/zh/oss/user-guide/what-is-oss)）。

-   **RPS 限制**：查询接口默认RPS为20。如需更高频查询或事件通知，建议[配置异步任务回调](https://help.aliyun.com/zh/model-studio/async-task-api)。

-   **更多操作**：如需批量查询、取消任务等操作，请参见[管理异步任务](https://help.aliyun.com/zh/model-studio/manage-asynchronous-tasks#f26499d72adsl)。


| ##### **请求参数** | ## 查询任务结果 将`{task_id}`完整替换为上一步接口返回的`task_id`的值。`task_id`查询有效期为24小时。 ``` curl -X GET https://dashscope.aliyuncs.com/api/v1/tasks/{task_id} \\ --header "Authorization: Bearer $DASHSCOPE_API_KEY" ``` |
| --- | --- |
| ###### **请求头（Headers）** |
| **Authorization** `*string*`**（必选）** 请求身份认证。接口使用阿里云百炼API-Key进行身份认证。示例值：Bearer sk-xxxx。 |
| ###### **URL路径参数（Path parameters）** |
| **task\\_id** `*string*`**（必选）** 任务ID。 |

| ##### **响应参数** | ## 任务执行成功 任务数据（如任务状态、图像URL等）仅保留24小时，超时后会被自动清除。请您务必及时保存生成的图像。 ``` { "request_id": "cf4a3304-fa4d-97b6-bc72-xxxxxx", "output": { "task_id": "18e7cde0-8c17-42aa-afc5-xxxxxx", "task_status": "SUCCEEDED", "submit_time": "2025-09-05 11:33:20.542", "scheduled_time": "2025-09-05 11:33:20.581", "end_time": "2025-09-05 11:33:40.807", "results": [ { "orig_prompt": "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。", "actual_prompt": "一副典雅庄重的对联悬挂于中式厅堂之中，对联左侧书写“义本生知人机同道善思新”，右侧书写“通云赋智乾坤启数高志远”，横批为“智启千问”，字体为飘逸洒脱的书法体，墨色浓淡相宜，展现出浓厚的文化气息与艺术美感。对联中央悬挂一幅中国风画作，描绘的是著名的岳阳楼景观，楼阁飞檐翘角，依水而建，远处山水氤氲，云雾缭绕，展现出古典诗意之美。\\n\\n整个画面背景为一个安静、布置典雅的中式房间，室内木质结构古朴，光线柔和，营造出宁静庄重的氛围。对联悬挂于房间正中墙面，下方为一长案几，案上摆放数件青花瓷器，器型古雅，纹饰精美，蓝白相间，与整体环境和谐统一。整体画面风格为中国水墨风，线条流畅，色彩淡雅，富有传统美学韵味。", "url": "https://dashscope-result-sz.oss-cn-shenzhen.aliyuncs.com/7d/xxx.png?Expires=xxxx" } ] }, "usage": { "image_count": 1 } } ``` ## 任务执行失败 若任务执行失败，task\\_status将置为 FAILED，并提供错误码和信息。请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)进行解决。 ``` { "request_id": "c61fe158-c0de-40f0-b4d9-964625119ba4", "output": { "task_id": "86ecf553-d340-4e21-xxxxxxxxx", "task_status": "FAILED", "submit_time": "2025-11-11 11:46:28.116", "scheduled_time": "2025-11-11 11:46:28.154", "end_time": "2025-11-11 11:46:28.255", "code": "InvalidParameter", "message": "xxxxxxxx" } } ``` |
| --- | --- |
| **output** `*object*` 任务输出信息。 **属性** **task\\_id** `*string*` 任务ID。查询有效期24小时。 **task\\_status** `*string*` 任务状态。 **枚举值** - PENDING：任务排队中 - RUNNING：任务处理中 - SUCCEEDED：任务执行成功 - FAILED：任务执行失败 - CANCELED：任务已取消 - UNKNOWN：任务不存在或状态未知 **submit\\_time** `*string*` 任务提交时间。格式为 YYYY-MM-DD HH:mm:ss.SSS。 **scheduled\\_time** `*string*` 任务执行时间。格式为 YYYY-MM-DD HH:mm:ss.SSS。 **end\\_time** `*string*` 任务完成时间。格式为 YYYY-MM-DD HH:mm:ss.SSS。 **results** `*array*` 任务结果列表，包括图像URL、prompt、部分任务执行失败报错信息等。 **属性** **orig\\_prompt** `*string*` 原始输入的prompt，对应请求参数`prompt`。 **actual\\_prompt** `*string*` 开启 prompt 智能改写后，返回实际使用的优化后 prompt。若未开启该功能，则不返回此字段。 **url** `*string*` 模型生成图像的URL地址。有效期为24小时，请及时下载并保存图像。 **code** `*string*` 请求失败的错误码。请求成功时不会返回此参数，详情请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)。 **message** `*string*` 请求失败的详细信息。请求成功时不会返回此参数，详情请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)。 |
| **usage** `*object*` 输出信息统计。只对成功的结果计数。 **属性** **image\\_count** `*integer*` 模型生成图像的数量，当前固定为1。 |
| **request\\_id** `*string*` 请求唯一标识。可用于请求明细溯源和问题排查。 |

### **DashScope SDK调用**

DashScope SDK目前已支持[Python](#a3ad9a3b6d9if)和[Java](#589b80853e6rn)。

SDK与HTTP接口的参数名基本一致，参数结构根据不同语言的SDK封装而定。异步调用参数说明可参考[HTTP调用](#42703589880ts)。

由于图像模型处理时间较长，底层服务采用异步方式。SDK在此基础上封装了两种调用模式：

-   **同步调用（阻塞模式）**： SDK会自动等待任务完成，然后直接返回最终结果，调用体验与常规同步调用一致。

-   **异步调用（非阻塞模式）**： 调用后将立即返回任务ID，需要用户根据该ID自行查询任务状态和最终结果。


#### **Python SDK调用**

**说明**

请先确认已安装最新版DashScope Python SDK，否则可能运行报错：[安装SDK](https://help.aliyun.com/zh/model-studio/install-sdk)。

## 同步调用

##### **请求示例**

```
from http import HTTPStatus
from urllib.parse import urlparse, unquote
from pathlib import PurePosixPath
import requests
from dashscope import ImageSynthesis
import os
import dashscope

# 以下为北京地域url，若使用新加坡地域的模型，需将url替换为：https://dashscope-intl.aliyuncs.com/api/v1
dashscope.base_http_api_url = 'https://dashscope.aliyuncs.com/api/v1'

prompt = "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。"

# 新加坡和北京地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
# 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx"
api_key = os.getenv("DASHSCOPE_API_KEY")

print('----同步调用，请等待任务执行----')
rsp = ImageSynthesis.call(api_key=api_key,
                          model="qwen-image-plus", # 当前仅qwen-image-plus、qwen-image模型支持异步接口
                          prompt=prompt,
                          negative_prompt=" ",
                          n=1,
                          size='1664*928',
                          prompt_extend=True,
                          watermark=False)
print(f'response: {rsp}')
if rsp.status_code == HTTPStatus.OK:
    # 在当前目录下保存图像
    for result in rsp.output.results:
        file_name = PurePosixPath(unquote(urlparse(result.url).path)).parts[-1]
        with open('./%s' % file_name, 'wb+') as f:
            f.write(requests.get(result.url).content)
else:
    print(f'同步调用失败, status_code: {rsp.status_code}, code: {rsp.code}, message: {rsp.message}')
```

##### 响应示例

> url 有效期24小时，请及时下载图像。

```
{
    "status_code": 200,
    "request_id": "03b1ef03-480d-4ea5-ba52-xxxxxx",
    "code": null,
    "message": "",
    "output": {
        "task_id": "3cefd9bc-fcb2-4de9-a8bc-xxxxxx",
        "task_status": "SUCCEEDED",
        "results": [
            {
                "url": "https://dashscope-result-sz.oss-cn-shenzhen.aliyuncs.com/xxx.png?Expires=xxxxxx",
                "orig_prompt": "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。",
                "actual_prompt": "一副典雅庄重的对联悬挂于中式厅堂正中，整体空间为安静、古色古香的中国传统布置。厅堂内木质家具沉稳大气，墙面为淡色仿古纸张质感，地面铺设深色木质地板，营造出宁静而庄重的氛围。对联以飘逸的书法字体书写，左侧上联为“义本生知人机同道善思新”，右侧下联为“通云赋智乾坤启数高志远”，横批“智启千问”，文字排列对称，墨色深邃，书法流畅有力，体现出浓厚的文化气息与哲思内涵。\n\n对联中央悬挂一幅中国风画作，内容为岳阳楼，楼阁依水而建，背景为浩渺洞庭湖，远处山峦起伏，云雾缭绕，画面采用传统水墨技法绘制，笔触细腻，意境悠远。画作下方为一张中式红木长桌，桌上错落摆放着几件青花瓷器，包括花瓶与茶具，瓷器釉色清透，纹饰典雅，与整体环境风格和谐统一。整体画面风格为中国古典水墨风，空间布局层次分明，氛围宁静雅致，展现出浓厚的东方文化底蕴。"
            }
        ],
        "submit_time": "2025-09-09 13:41:54.041",
        "scheduled_time": "2025-09-09 13:41:54.087",
        "end_time": "2025-09-09 13:42:22.596"
    },
    "usage": {
        "image_count": 1
    }
}
```

## 异步调用

##### 请求示例

```
from http import HTTPStatus
from urllib.parse import urlparse, unquote
from pathlib import PurePosixPath
import requests
from dashscope import ImageSynthesis
import os
import dashscope
import time

# 以下为北京地域url，若使用新加坡地域的模型，需将url替换为：https://dashscope-intl.aliyuncs.com/api/v1
dashscope.base_http_api_url = 'https://dashscope.aliyuncs.com/api/v1'

prompt = "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。"

# 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx"
# 新加坡和北京地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
api_key = os.getenv("DASHSCOPE_API_KEY")

def async_call():
    print('----创建任务----')
    task_info = create_async_task()
    print('----轮询任务状态----')
    poll_task_status(task_info)


# 创建异步任务
def create_async_task():
    rsp = ImageSynthesis.async_call(api_key=api_key,
                                    model="qwen-image-plus", # 当前仅qwen-image-plus、qwen-image模型支持异步接口
                                    prompt=prompt,
                                    negative_prompt=" ",
                                    n=1,
                                    size='1664*928',
                                    prompt_extend=True,
                                    watermark=False)
    print(rsp)
    if rsp.status_code == HTTPStatus.OK:
        print(rsp.output)
    else:
        print(f'创建任务失败, status_code: {rsp.status_code}, code: {rsp.code}, message: {rsp.message}')
    return rsp


# 轮询异步任务状态，每5秒查询一次，最多轮询1分钟
def poll_task_status(task):
    start_time = time.time()
    timeout = 60  # 1分钟超时
    
    while True:
        # 检查是否超时
        if time.time() - start_time > timeout:
            print('轮询超时（1分钟），任务未完成')
            return
            
        # 获取任务状态
        status_rsp = ImageSynthesis.fetch(task)
        print(f'任务状态查询结果: {status_rsp}')
        
        if status_rsp.status_code != HTTPStatus.OK:
            print(f'获取任务状态失败, status_code: {status_rsp.status_code}, code: {status_rsp.code}, message: {status_rsp.message}')
            return
        task_status = status_rsp.output.task_status
        print(f'当前任务状态: {task_status}')
        
        if task_status == 'SUCCEEDED':
            print('任务已完成，正在下载图像...')
            for result in status_rsp.output.results:
                file_name = PurePosixPath(unquote(urlparse(result.url).path)).parts[-1]
                with open(f'./{file_name}', 'wb+') as f:
                    f.write(requests.get(result.url).content)
                print(f'图像已保存为: {file_name}')
            break
        elif task_status == 'FAILED':
            print(f'任务执行失败, status: {task_status}, code: {status_rsp.code}, message: {status_rsp.message}')
            break
        elif task_status == 'PENDING' or task_status == 'RUNNING':
            print('任务正在进行中，5秒后继续查询...')
            time.sleep(5)
        elif task_status == 'CANCELED':
            print('任务已被取消。')
            break
        else:
            print(f'未知任务状态: {task_status}，5秒后继续查询...')
            time.sleep(5)

# 取消异步任务，只有处于PENDING状态的任务才可以取消
def cancel_task(task):
    rsp = ImageSynthesis.cancel(task)
    print(rsp)
    if rsp.status_code == HTTPStatus.OK:
        print(rsp.output.task_status)
    else:
        print(f'取消任务失败, status_code: {rsp.status_code}, code: {rsp.code}, message: {rsp.message}')


if __name__ == '__main__':
    async_call()
```

##### **响应示例**

1、创建任务的响应示例

```
{
	"status_code": 200,
	"request_id": "31b04171-011c-96bd-ac00-xxxxxx",
	"code": "",
	"message": "",
	"output": {
		"task_id": "4f90cf14-a34e-4eae-xxxxxxxx",
		"task_status": "PENDING",
		"results": []
	},
	"usage": null
}
```

2、查询任务结果的响应示例

> url 有效期24小时，请及时下载图像。

```
{
    "status_code": 200,
    "request_id": "03b1ef03-480d-4ea5-ba52-xxxxxx",
    "code": null,
    "message": "",
    "output": {
        "task_id": "3cefd9bc-fcb2-4de9-a8bc-xxxxxx",
        "task_status": "SUCCEEDED",
        "results": [
            {
                "url": "https://dashscope-result-sz.oss-cn-shenzhen.aliyuncs.com/xxx.png?Expires=xxxxxx",
                "orig_prompt": "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。",
                "actual_prompt": "一副典雅庄重的对联悬挂于中式厅堂正中，整体空间为安静、古色古香的中国传统布置。厅堂内木质家具沉稳大气，墙面为淡色仿古纸张质感，地面铺设深色木质地板，营造出宁静而庄重的氛围。对联以飘逸的书法字体书写，左侧上联为“义本生知人机同道善思新”，右侧下联为“通云赋智乾坤启数高志远”，横批“智启千问”，文字排列对称，墨色深邃，书法流畅有力，体现出浓厚的文化气息与哲思内涵。\n\n对联中央悬挂一幅中国风画作，内容为岳阳楼，楼阁依水而建，背景为浩渺洞庭湖，远处山峦起伏，云雾缭绕，画面采用传统水墨技法绘制，笔触细腻，意境悠远。画作下方为一张中式红木长桌，桌上错落摆放着几件青花瓷器，包括花瓶与茶具，瓷器釉色清透，纹饰典雅，与整体环境风格和谐统一。整体画面风格为中国古典水墨风，空间布局层次分明，氛围宁静雅致，展现出浓厚的东方文化底蕴。"
            }
        ],
        "submit_time": "2025-09-09 13:41:54.041",
        "scheduled_time": "2025-09-09 13:41:54.087",
        "end_time": "2025-09-09 13:42:22.596"
    },
    "usage": {
        "image_count": 1
    }
}
```

#### **Java SDK调用**

**说明**

请先确认已安装最新版DashScope Java SDK，否则可能运行报错：[安装SDK](https://help.aliyun.com/zh/model-studio/install-sdk)。

## 同步调用

##### 请求示例

```
// Copyright (c) Alibaba, Inc. and its affiliates.

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisListResult;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.task.AsyncTaskListParam;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.utils.JsonUtils;
import java.util.HashMap;
import java.util.Map;

public class Text2Image {
    static {
        // 以下为北京地域url，若使用新加坡地域的模型，需将url替换为：https://dashscope-intl.aliyuncs.com/api/v1
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
    }

    // 新加坡和北京地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
    // 若没有配置环境变量，请用百炼API Key将下行替换为：static String apiKey = "sk-xxx"
    static String apiKey = System.getenv("DASHSCOPE_API_KEY");

    public static void basicCall() throws ApiException, NoApiKeyException {
        String prompt = "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("prompt_extend", true);
        parameters.put("watermark", false);
        parameters.put("negative_prompt", " ");
        ImageSynthesisParam param =
                ImageSynthesisParam.builder()
                        .apiKey(apiKey)
                        // 当前仅qwen-image-plus、qwen-image模型支持异步接口
                        .model("qwen-image-plus")
                        .prompt(prompt)
                        .n(1)
                        .size("1664*928")
                        .parameters(parameters)
                        .build();

        ImageSynthesis imageSynthesis = new ImageSynthesis();
        ImageSynthesisResult result = null;
        try {
            System.out.println("---同步调用，请等待任务执行----");
            result = imageSynthesis.call(param);
        } catch (ApiException | NoApiKeyException e){
            throw new RuntimeException(e.getMessage());
        }
        System.out.println(JsonUtils.toJson(result));
    }

    public static void main(String[] args){
        try{
            basicCall();
        }catch(ApiException|NoApiKeyException e){
            System.out.println(e.getMessage());
        }
    }
}
```

##### **响应示例**

> url 有效期24小时，请及时下载图像。

```
{
    "request_id": "f2153409-3950-9b73-9980-xxxxxx",
    "output": {
        "task_id": "2fc2e1de-0245-442d-b664-xxxxxx",
        "task_status": "SUCCEEDED",
        "results": [
            {
                "orig_prompt": "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。",
                "actual_prompt": "一副典雅庄重的对联悬挂于中式厅堂中央，对联左侧书写“义本生知人机同道善思新”，右侧书写“通云赋智乾坤启数高志远”，横批为“智启千问”，整体采用飘逸洒脱的书法字体，墨色浓淡相宜，展现出浓厚的传统韵味。对联中间悬挂一幅中国风画作，描绘的是著名的岳阳楼景观：楼阁飞檐翘角，依水而建，远处湖光潋滟，烟波浩渺，天空中有几缕轻云缭绕，营造出诗意盎然的意境。背景房间为安静古典的中式布置，木质家具线条流畅，桌上摆放着数件青花瓷器，纹饰精美，釉色莹润。整体空间光线柔和，营造出庄重、宁静的文化氛围。画面风格为传统中国水墨风，笔触细腻，层次分明，充满古典美感。",
                "url": "https://dashscope-result-sz.oss-cn-shenzhen.aliyuncs.com/xxx.png?Expires=xxxx"
            }
        ]
    },
    "usage": {
        "image_count": 1
    }
}
```

## 异步调用

##### 请求示例

```
// Copyright (c) Alibaba, Inc. and its affiliates.

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.utils.JsonUtils;
import java.util.HashMap;
import java.util.Map;

public class Text2Image {

    static {
        // 以下为北京地域url，若使用新加坡地域的模型，需将url替换为：https://dashscope-intl.aliyuncs.com/api/v1
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
    }

    // 新加坡和北京地域的API Key不同。获取API Key：https://help.aliyun.com/zh/model-studio/get-api-key
    // 若没有配置环境变量，请用百炼API Key将下行替换为：static String apiKey = "sk-xxx"
    static String apiKey = System.getenv("DASHSCOPE_API_KEY");

    public void asyncCall() {
        System.out.println("---创建任务----");
        String taskId = this.createAsyncTask();
        System.out.println("--等待任务结束返回图像url----");
        this.waitAsyncTask(taskId);
    }

    public String createAsyncTask() {
        String prompt = "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。";
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("prompt_extend", true);
        parameters.put("watermark", false);
        parameters.put("negative_prompt", " ");
        ImageSynthesisParam param =
                ImageSynthesisParam.builder()
                        .apiKey(apiKey)
                        // 当前仅qwen-image-plus、qwen-image模型支持异步接口
                        .model("qwen-image-plus")
                        .prompt(prompt)
                        .n(1)
                        .size("1664*928")
                        .parameters(parameters)
                        .build();

        try {
            ImageSynthesisResult result = new ImageSynthesis().asyncCall(param);
            System.out.println(JsonUtils.toJson(result));
            String taskId = result.getOutput().getTaskId();
            System.out.println("task_id=" + taskId);
            return taskId;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public void waitAsyncTask(String taskId) {
        ImageSynthesis imageSynthesis = new ImageSynthesis();
        long startTime = System.currentTimeMillis();
        int timeout = 60 * 1000; // 1分钟超时
        int interval = 5 * 1000;  // 5秒轮询间隔

        while (true) {
            if (System.currentTimeMillis() - startTime > timeout) {
                System.out.println("轮询超时（1分钟），任务未完成");
                return;
            }

            try {
                ImageSynthesisResult result = imageSynthesis.fetch(taskId, apiKey);
                System.out.println("任务状态查询结果: " + JsonUtils.toJson(result));
                if (result.getOutput() == null) {
                    System.out.println("获取任务状态失败，输出结果为空");
                    return;
                }
                String taskStatus = result.getOutput().getTaskStatus();
                System.out.println("当前任务状态: " + taskStatus);
                switch (taskStatus) {
                    case "SUCCEEDED":
                        System.out.println("任务已完成");
                        System.out.println(JsonUtils.toJson(result));
                        return;
                    case "FAILED":
                        System.out.println("任务执行失败, status: " + taskStatus);
                        return;
                    case "PENDING":
                    case "RUNNING":
                        System.out.println("任务正在进行中，5秒后继续查询...");
                        Thread.sleep(interval);
                        break;
                    default:
                        System.out.println("未知任务状态: " + taskStatus + "，5秒后继续查询...");
                        Thread.sleep(interval);
                        break;
                }
            } catch (ApiException | NoApiKeyException e) {
                System.err.println("API调用异常: " + e.getMessage());
                return;
            } catch (InterruptedException e) {
                System.err.println("线程中断异常: " + e.getMessage());
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static void main(String[] args){
        Text2Image text2Image = new Text2Image();
        text2Image.asyncCall();
    }
}
```

##### 响应示例

1、创建任务的响应示例

```
{
	"request_id": "5dbf9dc5-4f4c-9605-85ea-542f97709ba8",
	"output": {
		"task_id": "7277e20e-aa01-4709-xxxxxxxx",
		"task_status": "PENDING"
	}
}
```

2、查询任务结果的响应示例

> url 有效期24小时，请及时下载图像。

```
{
    "request_id": "f2153409-3950-9b73-9980-xxxxxx",
    "output": {
        "task_id": "2fc2e1de-0245-442d-b664-xxxxxx",
        "task_status": "SUCCEEDED",
        "results": [
            {
                "orig_prompt": "一副典雅庄重的对联悬挂于厅堂之中，房间是个安静古典的中式布置，桌子上放着一些青花瓷，对联上左书“义本生知人机同道善思新”，右书“通云赋智乾坤启数高志远”， 横批“智启千问”，字体飘逸，在中间挂着一幅中国风的画作，内容是岳阳楼。",
                "actual_prompt": "一副典雅庄重的对联悬挂于中式厅堂中央，对联左侧书写“义本生知人机同道善思新”，右侧书写“通云赋智乾坤启数高志远”，横批为“智启千问”，整体采用飘逸洒脱的书法字体，墨色浓淡相宜，展现出浓厚的传统韵味。对联中间悬挂一幅中国风画作，描绘的是著名的岳阳楼景观：楼阁飞檐翘角，依水而建，远处湖光潋滟，烟波浩渺，天空中有几缕轻云缭绕，营造出诗意盎然的意境。背景房间为安静古典的中式布置，木质家具线条流畅，桌上摆放着数件青花瓷器，纹饰精美，釉色莹润。整体空间光线柔和，营造出庄重、宁静的文化氛围。画面风格为传统中国水墨风，笔触细腻，层次分明，充满古典美感。",
                "url": "https://dashscope-result-sz.oss-cn-shenzhen.aliyuncs.com/xxx.png?Expires=xxxx"
            }
        ]
    },
    "usage": {
        "image_count": 1
    }
}
```

## **计费与限流**

-   模型免费额度和计费单价请参见[模型价格](https://help.aliyun.com/zh/model-studio/model-pricing#11a4ac6ea62wt)。

-   模型限流请参见[千问（Qwen-Image）](https://help.aliyun.com/zh/model-studio/rate-limit#f812e7c63axvx)。

-   计费说明：按成功生成的 **图像张数** 计费。模型调用失败或处理错误不产生任何费用，也不消耗[新人免费额度](https://help.aliyun.com/zh/model-studio/new-free-quota)。


## **错误码**

如果模型调用失败并返回报错信息，请参见[错误信息](https://help.aliyun.com/zh/model-studio/error-code)进行解决。

## **常见问题**

#### **Q：prompt\_extend参数应该开启还是关闭？**

A：如果希望图像内容更多样化，由模型补充细节，建议开启此选项（默认）。如果图像细节更可控，建议关闭此选项，并参考[文生图Prompt指南](https://help.aliyun.com/zh/model-studio/text-to-image-prompt)进行优化，

#### **Q：qwen-image、qwen-image-plus、qwen-image-max、qwen-image-2.0、qwen-image-edit 等模型的区别是什么？**

A：

-   **图像生成与编辑融合模型：**同时支持文生图和图像编辑。

    -   `qwen-image-2.0-pro`、`qwen-image-2.0-pro-2026-03-03`：当前两者能力相同，Pro系列具备更专业的文字渲染能力、更细腻的真实质感，细腻刻画写实场景，以及更强的语义遵循能力。仅支持同步接口。

    -   `qwen-image-2.0`、`qwen-image-2.0-2026-03-03`：当前两者能力相同，加速版有效实现了模型效果和性能的最佳平衡。仅支持同步接口。

-   **文生图模型：**根据文本描述生成图像。

    -   `qwen-image-max`、`qwen-image-max-2025-12-30`：当前两者能力相同，相较于`qwen-image-plus`提升了生成图像的真实感与自然度，在人物质感、纹理细节和文字渲染等方面效果更佳。

    -   `qwen-image`、`qwen-image-plus`：当前两者能力相同，但`qwen-image-plus`的价格更优惠。

    -   `qwen-image-plus-2026-01-09`：千问图像生成的全新快照版模型，为`qwen-image-max`的蒸馏加速版，支持快速生成高质量图像。

-   **图像编辑模型**：  
    `qwen-image-edit`：根据输入的图像和文本指令，执行图生图、局部修改等操作，详情请参见[千问-图像编辑](https://help.aliyun.com/zh/model-studio/qwen-image-edit-api)。


### **Q：如何获取图像存储的访问域名白名单？**

A： 模型生成的图像存储于阿里云OSS，API将返回一个临时的公网URL。**若需要对该下载地址进行防火墙白名单配置**，请注意：由于底层存储会根据业务情况进行动态变更，为避免过期信息影响访问，文档不提供固定的OSS域名白名单。如有安全管控需求，请联系客户经理获取最新OSS域名列表。

/\* 调整 table 宽度 \*/ .aliyun-docs-content table.medium-width { max-width: 1018px; width: 100%; } .aliyun-docs-content table.table-no-border tr td:first-child { padding-left: 0; } .aliyun-docs-content table.table-no-border tr td:last-child { padding-right: 0; } /\* 支持吸顶 \*/ div:has(.aliyun-docs-content), .aliyun-docs-content .markdown-body { overflow: visible; } .stick-top { position: sticky; top: 46px; } /\*\*代码块字体\*\*/ /\* 减少表格中的代码块 margin，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body table .help-code-block { margin: 0 !important; } /\* 减少表格中的代码块字号，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body .help-code-block pre { font-size: 12px !important; } /\* 减少表格中的代码块字号，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body .help-code-block pre code { font-size: 12px !important; } /\*\* API Reference 表格 \*\*/ .aliyun-docs-content table.api-reference tr td:first-child { margin: 0px; border-bottom: 1px solid #d8d8d8; } .aliyun-docs-content table.api-reference tr:last-child td:first-child { border-bottom: none; } .aliyun-docs-content table.api-reference p { color: #6e6e80; } .aliyun-docs-content table.api-reference b, i { color: #181818; } .aliyun-docs-content table.api-reference .collapse { border: none; margin-top: 4px; margin-bottom: 4px; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold { padding: 0; } .aliyun-docs-content table.api-reference .collapse .expandable-title { padding: 0; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold .title { margin-left: 16px; } .aliyun-docs-content table.api-reference .collapse .expandable-title .title { margin-left: 16px; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold i.icon { position: absolute; color: #777; font-weight: 100; } .aliyun-docs-content table.api-reference .collapse .expandable-title i.icon { position: absolute; color: #777; font-weight: 100; } .aliyun-docs-content table.api-reference .collapse.expanded .expandable-content { padding: 10px 14px 10px 14px !important; margin: 0; border: 1px solid #e9e9e9; } .aliyun-docs-content table.api-reference .collapse .expandable-title-bold b { font-size: 13px; font-weight: normal; color: #6e6e80; } .aliyun-docs-content table.api-reference .collapse .expandable-title b { font-size: 13px; font-weight: normal; color: #6e6e80; } .aliyun-docs-content table.api-reference .tabbed-content-box { border: none; } .aliyun-docs-content table.api-reference .tabbed-content-box section { padding: 8px 0 !important; } .aliyun-docs-content table.api-reference .tabbed-content-box.mini .tab-box { /\* position: absolute; left: 40px; right: 0; \*/ } .aliyun-docs-content .margin-top-33 { margin-top: 33px !important; } .aliyun-docs-content .two-codeblocks pre { max-height: calc(50vh - 136px) !important; height: auto; } .expandable-content section { border-bottom: 1px solid #e9e9e9; padding-top: 6px; padding-bottom: 4px; } .expandable-content section:last-child { border-bottom: none; } .expandable-content section:first-child { padding-top: 0; }

/\* 让引用上下间距调小，避免内容显示过于稀疏 \*/ .unionContainer .markdown-body blockquote { margin: 4px 0; } .aliyun-docs-content table.qwen blockquote { border-left: none; /\* 添加这一行来移除表格里的引用文字的左侧边框 \*/ padding-left: 5px; /\* 左侧内边距 \*/ margin: 4px 0; } /\* 让表格显示成类似钉钉文档的分栏卡片 \*/ table.help-table-card td { border: 10px solid #FFF !important; background: #F4F6F9; padding: 16px !important; vertical-align: top; } /\* 减少表格中的代码块 margin，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body table .help-code-block { margin: 0 !important; } /\* 减少表格中的代码块字号，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body .help-code-block pre { font-size: 12px !important; } /\* 减少表格中的代码块字号，让表格信息显示更紧凑 \*/ .unionContainer .markdown-body .help-code-block pre code { font-size: 12px !important; } /\* 表格中的引用上下间距调小，避免内容显示过于稀疏 \*/ .unionContainer .markdown-body table blockquote { margin: 4px 0 0 0; } /\*表格图片设置为块元素（独占一行），居中展示，鼠标放在图片上可以点击查看原图\*/ .unionContainer .markdown-body .image.break { margin: 0px; display: inline-block; vertical-align: middle }

---

## 3. Kimi (月之暗面)
> ## Documentation Index
> Fetch the complete documentation index at: https://platform.kimi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# API 概述

## 服务地址

```
https://api.moonshot.cn
```

Kimi 开放平台提供兼容 OpenAI 协议的 HTTP API，您可以直接使用 OpenAI SDK 接入。

使用 SDK 时，`base_url` 设置为 `https://api.moonshot.cn/v1`；直接调用 HTTP 端点时，完整路径如 `https://api.moonshot.cn/v1/chat/completions`。

## 兼容 OpenAI

我们的 API 在请求/响应格式上兼容 OpenAI Chat Completions API。这意味着：

* 可以直接使用 OpenAI 官方 SDK（Python / Node.js）
* 支持大多数兼容 OpenAI 的第三方工具和框架（LangChain、Dify、Coze 等）
* 只需将 `base_url` 指向 `https://api.moonshot.cn/v1` 即可切换

<Note>
  部分参数为 Kimi 专有扩展：`thinking` 参数需要通过 SDK 的 `extra_body` 传递；`partial` 是写在 messages 中 assistant 消息上的字段（`"partial": true`），不是顶层请求参数。详见[工具调用](/api/tool-use)和 [Partial Mode](/api/partial)。
</Note>

## 认证

所有 API 请求需要在 HTTP 头中携带 API Key：

```
Authorization: Bearer $MOONSHOT_API_KEY
```

API Key 可在 [Kimi 开放平台控制台](https://platform.kimi.com/console/api-keys) 创建和管理。

<Warning>
  API Key 是敏感信息，请妥善保管。不要在客户端代码、公开仓库或日志中暴露。建议通过环境变量管理。
</Warning>

## SDK 安装

<CodeGroup>
  ```bash Python theme={null}
  pip install --upgrade 'openai>=1.0'
  ```

  ```bash Node.js theme={null}
  npm install openai
  ```
</CodeGroup>

初始化客户端：

<CodeGroup>
  ```python Python theme={null}
  from openai import OpenAI

client = OpenAI(
api_key="$MOONSHOT_API_KEY",
base_url="https://api.moonshot.cn/v1",
)
  ```

  ```javascript Node.js theme={null}
  const OpenAI = require("openai");

  const client = new OpenAI({
      apiKey: "$MOONSHOT_API_KEY",
      baseURL: "https://api.moonshot.cn/v1",
  });
  ```
</CodeGroup>

<Note>
  Python 版本需 ≥ 3.7.1，Node.js 版本需 ≥ 18，OpenAI SDK 版本需 ≥ 1.0.0。

  ```bash theme={null}
  python -c 'import openai; print("version =", openai.__version__)'
  ```
</Note>

## 通用请求头

| 请求头             | 值                          | 说明    |
| --------------- | -------------------------- | ----- |
| `Content-Type`  | `application/json`         | 请求体格式 |
| `Authorization` | `Bearer $MOONSHOT_API_KEY` | 认证令牌  |

## 错误处理

请求失败时返回 JSON 格式的错误响应，包含 `error.type` 和 `error.message` 字段。常见的 HTTP 状态码包括 400（请求错误）、401（认证失败）、429（速率限制）、500（服务端错误）等。

完整的错误类型、错误消息和排障建议，请参阅[错误说明](/api/errors)。

## API 端点一览

| 端点                                    | 方法     | 说明                            |
| ------------------------------------- | ------ | ----------------------------- |
| `/v1/chat/completions`                | POST   | [创建对话补全](/api/chat)           |
| `/v1/models`                          | GET    | [列出模型](/api/list-models)      |
| `/v1/tokenizers/estimate-token-count` | POST   | [计算 Token](/api/estimate)     |
| `/v1/users/me/balance`                | GET    | [查询余额](/api/balance)          |
| `/v1/files`                           | POST   | [上传文件](/api/files-upload)     |
| `/v1/files`                           | GET    | [列出文件](/api/files-list)       |
| `/v1/files/{file_id}`                 | GET    | [获取文件信息](/api/files-retrieve) |
| `/v1/files/{file_id}`                 | DELETE | [删除文件](/api/files-delete)     |
| `/v1/files/{file_id}/content`         | GET    | [获取文件内容](/api/files-content)  |

## 下一步

<CardGroup cols={2}>
  <Card title="快速开始" icon="rocket" href="/api/quickstart">
    发送第一个 API 请求
  </Card>

  <Card title="模型总览" icon="cubes" href="/api/models-overview">
    了解各模型的能力和参数差异
  </Card>

  <Card title="工具调用" icon="wrench" href="/api/tool-use">
    让模型调用外部函数
  </Card>

  <Card title="创建对话补全" icon="code" href="/api/chat">
    完整的端点参数参考
  </Card>
</CardGroup>


> ## Documentation Index
> Fetch the complete documentation index at: https://platform.kimi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 快速开始

## 单轮对话

使用 OpenAI SDK 和 cURL 与 Chat Completions API 进行交互：

<CodeGroup>
  ```python Python theme={null}
  from openai import OpenAI

client = OpenAI(
api_key = "$MOONSHOT_API_KEY",
base_url = "https://api.moonshot.cn/v1",
)

completion = client.chat.completions.create(
model = "kimi-k2.6",
messages = [
{"role": "system", "content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"},
{"role": "user", "content": "你好，我叫李雷，1+1等于多少？"}
]
)

print(completion.choices[0].message.content)
  ```

  ```bash cURL theme={null}
  curl https://api.moonshot.cn/v1/chat/completions \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $MOONSHOT_API_KEY" \
      -d '{
          "model": "kimi-k2.6",
          "messages": [
              {"role": "system", "content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"},
              {"role": "user", "content": "你好，我叫李雷，1+1等于多少？"}
          ]
     }'
  ```

  ```javascript Node.js theme={null}
  const OpenAI = require("openai");

  const client = new OpenAI({
      apiKey: "$MOONSHOT_API_KEY",
      baseURL: "https://api.moonshot.cn/v1",
  });

  async function main() {
      const completion = await client.chat.completions.create({
          model: "kimi-k2.6",
          messages: [
              {role: "system", content: "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"},
              {role: "user", content: "你好，我叫李雷，1+1等于多少？"}
          ]
      });
      console.log(completion.choices[0].message.content);
  }

  main();
  ```
</CodeGroup>

其中 `$MOONSHOT_API_KEY` 需要替换为您在平台上创建的 API Key。

## 多轮对话

将模型输出的结果继续作为输入的一部分以实现多轮对话：

<CodeGroup>
  ```python Python theme={null}
  from openai import OpenAI

client = OpenAI(
api_key = "$MOONSHOT_API_KEY",
base_url = "https://api.moonshot.cn/v1",
)

history = [
{"role": "system", "content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"}
]

def chat(query, history):
history.append({
"role": "user",
"content": query
})
completion = client.chat.completions.create(
model="kimi-k2.6",
messages=history
)
result = completion.choices[0].message.content
history.append({
"role": "assistant",
"content": result
})
return result

print(chat("地球的自转周期是多少？", history))
print(chat("月球呢？", history))
  ```

  ```javascript Node.js theme={null}
  const OpenAI = require("openai");

  const client = new OpenAI({
      apiKey: "$MOONSHOT_API_KEY",
      baseURL: "https://api.moonshot.cn/v1",
  });

  let history = [{"role": "system", "content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"}];

  async function chat(prompt) {
      history.push({
          role: "user", content: prompt
      })
      const completion = await client.chat.completions.create({
          model: "kimi-k2.6",
          messages: history,
      });
      history = history.concat(completion.choices[0].message)
      return completion.choices[0].message.content;
  }

  async function main() {
      let reply = await chat("地球的自转周期是多少？")
      console.log(reply);
      reply = await chat("月球呢？")
      console.log(reply);
  }

  main();
  ```
</CodeGroup>

<Note>
  随着对话的进行，模型每次需要传入的 token 都会线性增加，必要时，需要一些策略进行优化，例如只保留最近几轮对话。
</Note>

## 流式输出

使用 `stream: true` 启用流式返回，获得更好的用户体验：

<CodeGroup>
  ```python Python theme={null}
  from openai import OpenAI

client = OpenAI(
api_key = "$MOONSHOT_API_KEY",
base_url = "https://api.moonshot.cn/v1",
)

response = client.chat.completions.create(
model="kimi-k2.6",
messages=[
{
"role": "system",
"content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。",
},
{"role": "user", "content": "你好，我叫李雷，1+1等于多少？"},
],
stream=True,
)

collected_messages = []
for idx, chunk in enumerate(response):
chunk_message = chunk.choices[0].delta
if not chunk_message.content:
continue
collected_messages.append(chunk_message)
print(f"#{idx}: {''.join([m.content for m in collected_messages])}")
print(f"Full conversation received: {''.join([m.content for m in collected_messages])}")
  ```

  ```bash cURL theme={null}
  curl https://api.moonshot.cn/v1/chat/completions \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $MOONSHOT_API_KEY" \
    -d '{
      "model": "kimi-k2.6",
      "messages": [
        {
          "role": "system",
          "content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"
        },
        {
          "role": "user",
          "content": "你好，我叫李雷，1+1等于多少？"
        }
      ],
      "stream": true
    }'
  ```

  ```javascript Node.js theme={null}
  const OpenAI = require("openai");

  const client = new OpenAI({
      apiKey: "$MOONSHOT_API_KEY",
      baseURL: "https://api.moonshot.cn/v1",
  });

  async function main() {
      const stream = await client.chat.completions.create({
          model: "kimi-k2.6",
          messages: [
              {
                  role: "system",
                  content: "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"
              },
              {
                  role: "user",
                  content: "你好，我叫李雷，1+1等于多少？"
              }
          ],
          stream: true,
      });

      for await (const chunk of stream) {
          const delta = chunk.choices[0].delta;
          if (delta.content) {
              process.stdout.write(delta.content);
          }
      }
  }

  main();
  ```
</CodeGroup>


> ## Documentation Index
> Fetch the complete documentation index at: https://platform.kimi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 模型参数参考

export const DocTable = ({columns = [], rows = []}) => {
return <div className="doc-table-wrap">
<table className="doc-table">
{columns.length > 0 ? <colgroup>
{columns.map((column, index) => <col key={index} style={column.width ? {
width: column.width
} : undefined} />)}
</colgroup> : null}
<thead>
<tr>
{columns.map((column, index) => <th key={index}>{column.title}</th>)}
</tr>
</thead>
<tbody>
{rows.map((row, rowIndex) => <tr key={rowIndex}>
{row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}
</tr>)}
</tbody>
</table>
</div>;
};

不同模型系列对 Chat Completions API 参数有不同的默认值和约束。完整的模型列表请参阅[模型列表](/models)。

## 参数对比

<DocTable
columns={[
{ title: "参数", width: "18%" },
{ title: "kimi-k2.6", width: "18%" },
{ title: "kimi-k2 系列", width: "20%" },
{ title: "kimi-k2-thinking 系列", width: "24%" },
{ title: "moonshot-v1 系列", width: "20%" },
]}
rows={[
[<code>temperature</code>, <strong>不可修改</strong>, "0.6", "1.0", "0.0"],
[<code>top_p</code>, <>0.95 <strong>不可改</strong></>, "1.0", "1.0", "1.0"],
[<code>n</code>, <>1 <strong>不可改</strong></>, "1（最大 5）", "1（最大 5）", "1（最大 5）"],
[<code>presence_penalty</code>, <>0 <strong>不可改</strong></>, "0（可修改）", "0（可修改）", "0（可修改）"],
[<code>frequency_penalty</code>, <>0 <strong>不可改</strong></>, "0（可修改）", "0（可修改）", "0（可修改）"],
[<code>thinking</code>, "支持", "—", "—", "—"],
]}
/>

<Note>
  当 `temperature` 接近 0 时，`n` 只能为 1，否则将返回 `invalid_request_error`。
</Note>

## Kimi K2.6 — thinking 参数

Kimi K2.6 支持通过 `thinking` 参数控制是否启用深度思考。接受 `{"type": "enabled"}` 或 `{"type": "disabled"}`。

由于 OpenAI SDK 没有原生的 `thinking` 参数，需要使用 `extra_body` 传递：

<CodeGroup>
  ```python Python theme={null}
  completion = client.chat.completions.create(
      model="kimi-k2.6",
      messages=[
          {"role": "user", "content": "你好"}
      ],
      extra_body={
          "thinking": {"type": "disabled"}
      },
      max_tokens=1024*32,
  )
  ```

  ```bash cURL theme={null}
  curl https://api.moonshot.cn/v1/chat/completions \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $MOONSHOT_API_KEY" \
    -d '{
      "model": "kimi-k2.6",
      "messages": [
        {"role": "user", "content": "你好"}
      ],
      "thinking": {"type": "disabled"}
    }'
  ```
</CodeGroup>

> ## Documentation Index
> Fetch the complete documentation index at: https://platform.kimi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 工具调用

学会使用工具是智能的一个重要特征，在 Kimi 大模型中我们同样如此。Tool Use 或者 Function Calling 是 Kimi 大模型的一个重要功能，在调用 API 使用模型服务时，您可以在 Messages 中描述工具或函数，并让 Kimi 大模型智能地选择输出一个包含调用一个或多个函数所需的参数的 JSON 对象，实现让 Kimi 大模型链接使用外部工具的目的。

下面是一个简单的工具调用的例子：

```json theme={null}
{
  "model": "kimi-k2.6",
  "messages": [
    {
      "role": "user",
      "content": "编程判断 3214567 是否是素数。"
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "CodeRunner",
        "description": "代码执行器，支持运行 python 和 javascript 代码",
        "parameters": {
          "properties": {
            "language": {
              "type": "string",
              "enum": ["python", "javascript"]
            },
            "code": {
              "type": "string",
              "description": "代码写在这里"
            }
          },
          "type": "object"
        }
      }
    }
  ]
}
```

<Frame>
  <img src="https://mintcdn.com/moonshotcn/3bxMseHtiQ3oOhqL/assets/images/tooluse_whiteboard_example.png?fit=max&auto=format&n=3bxMseHtiQ3oOhqL&q=85&s=198f50ed66d4c6d9bd84ca4b4b745031" alt="上面例子的示意图" width="835" height="644" data-path="assets/images/tooluse_whiteboard_example.png" />
</Frame>

其中在 tools 字段，我们可以增加一组可选的工具列表。

每个工具列表必须包括一个类型，在 function 结构体中我们需要包括 name（它的需要遵守这样的正则表达式作为规范: ^\[a-zA-Z\_]\[a-zA-Z0-9-\_]{2,63}\$），这个名字如果是一个容易理解的英文可能会更加被模型所接受。以及一段 description 或者 enum，其中 description 部分介绍它能做什么功能，方便模型来判断和选择。
function 结构体中必须要有个 parameters 字段，parameters 的 root 必须是一个 object，内容是一个 json schema 的子集（详见 [MFJS 规范](https://github.com/MoonshotAI/walle/blob/main/docs/mfjs-spec.zh.md)）。

此外，每个 function 支持 `strict` 参数（boolean 类型，可选），用于控制是否严格按 `parameters` 定义的 JSON Schema 约束工具调用参数的输出：

* **`true`（默认，不传等价于 `true`）**：系统会严格按照 `parameters` schema 约束输出，schema 需符合 MFJS 规范
* **`false`（需显式传入）**：仅保证输出为合法 JSON 对象，不强制约束内部结构

tools 的 function 个数目前不得超过 128 个。

如果您在使用 JSON Schema 时遇到校验问题，欢迎到 [walle GitHub Issues](https://github.com/MoonshotAI/walle/issues) 提交反馈。

和别的 API 一样，我们可以通过 Chat API 调用它。

<CodeGroup>
  ```python Python expandable theme={null}
  from openai import OpenAI

client = OpenAI(
api_key = "$MOONSHOT_API_KEY",
base_url = "https://api.moonshot.cn/v1",
)

completion = client.chat.completions.create(
model = "kimi-k2.6",
messages = [
{"role": "system", "content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"},
{"role": "user", "content": "编程判断 3214567 是否是素数。"}
],
tools = [{
"type": "function",
"function": {
"name": "CodeRunner",
"description": "代码执行器，支持运行 python 和 javascript 代码",
"parameters": {
"properties": {
"language": {
"type": "string",
"enum": ["python", "javascript"]
},
"code": {
"type": "string",
"description": "代码写在这里"
}
},
"type": "object"
}
}
}]
)

print(completion.choices[0].message)
  ```

  ```bash cURL expandable theme={null}
  curl https://api.moonshot.cn/v1/chat/completions \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $MOONSHOT_API_KEY" \
      -d '{
          "model": "kimi-k2.6",
          "messages": [
              {"role": "system", "content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"},
              {"role": "user", "content": "编程判断 3214567 是否是素数。"}
          ],
          "tools": [{
              "type": "function",
              "function": {
                  "name": "CodeRunner",
                  "description": "代码执行器，支持运行 python 和 javascript 代码",
                  "parameters": {
                      "properties": {
                          "language": {
                              "type": "string",
                              "enum": ["python", "javascript"]
                          },
                          "code": {
                              "type": "string",
                              "description": "代码写在这里"
                          }
                      },
                  "type": "object"
                  }
              }
          }]
     }'
  ```

  ```javascript Node.js expandable theme={null}
  const OpenAI = require("openai");

  const client = new OpenAI({
      apiKey: "$MOONSHOT_API_KEY",
      baseURL: "https://api.moonshot.cn/v1",
  });

  async function main() {
      const completion = await client.chat.completions.create({
          model: "kimi-k2.6",
          messages: [
              {"role": "system", "content": "你是 Kimi，由 Moonshot AI 提供的人工智能助手，你更擅长中文和英文的对话。你会为用户提供安全，有帮助，准确的回答。同时，你会拒绝一切涉及恐怖主义，种族歧视，黄色暴力等问题的回答。Moonshot AI 为专有名词，不可翻译成其他语言。"},
              {"role": "user", "content": "编程判断 3214567 是否是素数。"}
          ],
          tools: [{
              "type": "function",
              "function": {
                  "name": "CodeRunner",
                  "description": "代码执行器，支持运行 python 和 javascript 代码",
                  "parameters": {
                      "properties": {
                          "language": {
                              "type": "string",
                              "enum": ["python", "javascript"]
                          },
                          "code": {
                              "type": "string",
                              "description": "代码写在这里"
                          }
                      },
                  "type": "object"
                  }
              }
          }]
      });
      console.log(completion.choices[0].message);
  }

  main();
  ```
</CodeGroup>

### 工具配置

你也可以使用一些 Agent 平台例如 [Coze](https://coze.cn/)、[Bisheng](https://github.com/dataelement/bisheng)、[Dify](https://github.com/langgenius/dify/) 和 [LangChain](https://github.com/langchain-ai/langchain) 等框架来创建和管理这些工具，并配合 Kimi 大模型设计更加复杂的工作流。



---

## 4. 智谱 (GLM)
> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 使用概述

<Info>
  API 参考文档描述了您可以用来与 智谱AI 开放平台交互的 RESTful API 详情信息，您也可以通过点击 Try it 按钮调试 API。
</Info>

智谱AI 开放平台提供标准的 HTTP API 接口，支持多种编程语言和开发环境，同时也提供 [SDKs](/cn/guide/develop/python/introduction) 方便开发者调用。

## API 端点

智谱AI 开放平台的通用 API 端点如下：

```
https://open.bigmodel.cn/api/paas/v4
```

<Warning>
  注意：使用 [GLM 编码套餐](/cn/coding-plan/overview) 时，需要配置专属的 \
  Coding 端点 - [https://open.bigmodel.cn/api/coding/paas/v4](https://open.bigmodel.cn/api/coding/paas/v4) \
  而非通用端点 - [https://open.bigmodel.cn/api/paas/v4](https://open.bigmodel.cn/api/paas/v4) \
  注意：Coding API 端点仅限 Coding 场景，并不适用通用 API 场景，请区分使用。
</Warning>

## 身份验证

开放平台 API 使用标准的 **HTTP Bearer** 进行身份验证。
认证需要 API 密钥，您可以在 [API Keys 页面](https://bigmodel.cn/usercenter/proj-mgmt/apikeys) 创建或管理。

API 密钥应通过 HTTP 请求头中的 HTTP Bearer 身份验证提供。

```
Authorization: Bearer YOUR_API_KEY
```

<Tip>
  建议将 API Key 设置为环境变量替代硬编码到代码中，以提高安全性。
</Tip>

## 调试工具

在 API 详情页面，右上方有丰富的 **调用示例**，可以点击切换查看不同场景的示例。<br />
提供 API 调试工具允许开发者快速尝试 API 调用。只需在 API 详情页面点击 **Try it** 即可开始。

* 在 API 详情页面，有许多交互选项，有些交互按钮可能不容易发现需要您留意，例如 **切换输入类型下拉框**、**切换标签页** 和 **添加新内容** 等。
* 您可以点击 **Add an item** 或 **Add new property** 来添加 API 需要的更多属性。
* **注意**: 当切换不同标签页后，您需要重新输入或重新切换之前的属性值。

## 调用示例

<Tabs>
  <Tab title="cURL">
    ```bash theme={null}
    curl -X POST "https://open.bigmodel.cn/api/paas/v4/chat/completions" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer YOUR_API_KEY" \
    -d '{
        "model": "glm-5.1",
        "messages": [
            {
                "role": "system",
                "content": "你是一个有用的AI助手。"
            },
            {
                "role": "user",
                "content": "你好，请介绍一下自己。"
            }
        ],
        "temperature": 1.0,
        "stream": true
    }'
    ```
  </Tab>

  <Tab title="Python SDK">
    **安装 SDK**

    ```bash theme={null}
    # 安装最新版本
    pip install zai-sdk

    # 或指定版本
    pip install zai-sdk==0.2.2
    ```

    **验证安装**

    ```python theme={null}
    import zai
    print(zai.__version__)
    ```

    **使用示例**

    ```python theme={null}
    from zai import ZhipuAiClient

    # 初始化客户端
    client = ZhipuAiClient(api_key="YOUR_API_KEY")

    # 创建聊天完成请求
    response = client.chat.completions.create(
        model="glm-5.1",
        messages=[
            {
                "role": "system",
                "content": "你是一个有用的AI助手。"
            },
            {
                "role": "user",
                "content": "你好，请介绍一下自己。"
            }
        ],
        temperature=0.6
    )

    # 获取回复
    print(response.choices[0].message.content)
    ```
  </Tab>

  <Tab title="Java SDK">
    **安装 SDK**

    **Maven**

    ```xml theme={null}
    <dependency>
        <groupId>ai.z.openapi</groupId>
        <artifactId>zai-sdk</artifactId>
        <version>0.3.3</version>
    </dependency>
    ```

    **Gradle (Groovy)**

    ```groovy theme={null}
    implementation 'ai.z.openapi:zai-sdk:0.3.3'
    ```

    **使用示例**

    ```java theme={null}
    import ai.z.openapi.ZhipuAiClient;
    import ai.z.openapi.service.model.*;
    import java.util.Arrays;

    public class QuickStart {
        public static void main(String[] args) {
            // 初始化客户端
            ZhipuAiClient client = ZhipuAiClient.builder().ofZHIPU()
                .apiKey("YOUR_API_KEY")
                .build();

            // 创建聊天完成请求
            ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model("glm-5.1")
                .messages(Arrays.asList(
                    ChatMessage.builder()
                        .role(ChatMessageRole.USER.value())
                        .content("Hello, who are you?")
                        .build()
                ))
                .stream(false)
                .temperature(0.6f)
                .maxTokens(1024)
                .build();

            // 发送请求
            ChatCompletionResponse response = client.chat().createChatCompletion(request);

            // 获取回复
            System.out.println(response.getData().getChoices().get(0).getMessage());
        }
    }
    ```
  </Tab>

  <Tab title="Python SDK(旧)">
    **安装 SDK**

    ```bash theme={null}
    # 安装最新版本
    pip install zhipuai

    # 或指定版本
    pip install zhipuai==2.1.5.20250726
    ```

    **验证安装**

    ```python theme={null}
    import zhipuai
    print(zhipuai.__version__)
    ```

    **使用示例**

    ```python theme={null}
    from zhipuai import ZhipuAI

    client = ZhipuAI(api_key="YOUR_API_KEY")
    response = client.chat.completions.create(
        model="glm-5.1",
        messages=[
            {
                "role": "system",
                "content": "你是一个有用的AI助手。"
            },
            {
                "role": "user",
                "content": "你好，请介绍一下自己。"
            }
        ]
    )
    print(response.choices[0].message.content)
    ```
  </Tab>
</Tabs>



> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 对话补全

> 和 [指定模型](/cn/guide/start/model-overview) 对话，模型根据请求给出响应。支持多种模型，支持多模态（文本、图片、音频、视频、文件），流式和非流式输出，可配置采样，温度，最大令牌数，工具调用等。



## OpenAPI

````yaml /openapi/openapi.json post /paas/v4/chat/completions
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/chat/completions:
    post:
      tags:
        - 模型 API
      summary: 对话补全
      description: >-
        和 [指定模型](/cn/guide/start/model-overview)
        对话，模型根据请求给出响应。支持多种模型，支持多模态（文本、图片、音频、视频、文件），流式和非流式输出，可配置采样，温度，最大令牌数，工具调用等。
      requestBody:
        content:
          application/json:
            schema:
              oneOf:
                - $ref: '#/components/schemas/ChatCompletionTextRequest'
                  title: 文本模型
                - $ref: '#/components/schemas/ChatCompletionVisionRequest'
                  title: 视觉模型
                - $ref: '#/components/schemas/ChatCompletionAudioRequest'
                  title: 音频模型
                - $ref: '#/components/schemas/ChatCompletionHumanOidRequest'
                  title: 角色模型
            examples:
              基础调用示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: system
                      content: 你是一个有用的AI助手。
                    - role: user
                      content: 请介绍一下人工智能的发展历程。
                  temperature: 1
                  stream: false
              流式调用示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: user
                      content: 写一首关于春天的诗。
                  temperature: 1
                  stream: true
              深度思考示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: user
                      content: 写一首关于春天的诗。
                  thinking:
                    type: enabled
                  stream: true
              多轮对话示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: system
                      content: 你是一个专业的编程助手
                    - role: user
                      content: 什么是递归？
                    - role: assistant
                      content: 递归是一种编程技术，函数调用自身来解决问题...
                    - role: user
                      content: 能给我一个 Python 递归的例子吗？
                  stream: true
              图片理解示例:
                value:
                  model: glm-5v-turbo
                  messages:
                    - role: user
                      content:
                        - type: image_url
                          image_url:
                            url: https://cdn.bigmodel.cn/static/logo/register.png
                        - type: image_url
                          image_url:
                            url: https://cdn.bigmodel.cn/static/logo/api-key.png
                        - type: text
                          text: What are the pics talk about
              视频理解示例:
                value:
                  model: glm-5v-turbo
                  messages:
                    - role: user
                      content:
                        - type: video_url
                          video_url:
                            url: >-
                              https://cdn.bigmodel.cn/agent-demos/lark/113123.mov
                        - type: text
                          text: What are the video show about?
              文件理解示例:
                value:
                  model: glm-5v-turbo
                  messages:
                    - role: user
                      content:
                        - type: file_url
                          file_url:
                            url: https://cdn.bigmodel.cn/static/demo/demo2.txt
                        - type: file_url
                          file_url:
                            url: https://cdn.bigmodel.cn/static/demo/demo1.pdf
                        - type: text
                          text: What are the files show about?
              音频对话示例:
                value:
                  model: glm-4-voice
                  messages:
                    - role: user
                      content:
                        - type: text
                          text: 你好，这是我的语音输入测试，请慢速复述一遍
                        - type: input_audio
                          input_audio:
                            data: base64_voice_xxx
                            format: wav
              Function Call 示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: user
                      content: 今天北京的天气怎么样？
                  tools:
                    - type: function
                      function:
                        name: get_weather
                        description: 获取指定城市的天气信息
                        parameters:
                          type: object
                          properties:
                            city:
                              type: string
                              description: 城市名称
                          required:
                            - city
                  tool_choice: auto
                  temperature: 0.3
        required: true
      responses:
        '200':
          description: 业务处理成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ChatCompletionResponse'
            text/event-stream:
              schema:
                $ref: '#/components/schemas/ChatCompletionChunk'
        default:
          description: 请求失败
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    ChatCompletionTextRequest:
      required:
        - model
        - messages
      type: object
      description: 普通对话模型请求，支持纯文本对话和工具调用
      properties:
        model:
          type: string
          description: >-
            调用的普通对话模型代码。`GLM-5.1` 和 `GLM-5-Turbo` 是最新的旗舰模型系列。`GLM-5`
            系列提供了复杂推理、超长上下文、极快推理速度等多款模型。
          example: glm-5.1
          default: glm-5.1
          enum:
            - glm-5.1
            - glm-5-turbo
            - glm-5
            - glm-4.7
            - glm-4.7-flash
            - glm-4.7-flashx
            - glm-4.6
            - glm-4.5-air
            - glm-4.5-airx
            - glm-4.5-flash
            - glm-4-flash-250414
            - glm-4-flashx-250414
        messages:
          type: array
          description: >-
            对话消息列表，包含当前对话的完整上下文信息。每条消息都有特定的角色和内容，模型会根据这些消息生成回复。消息按时间顺序排列，支持四种角色：`system`（系统消息，用于设定`AI`的行为和角色）、`user`（用户消息，来自用户的输入）、`assistant`（助手消息，来自`AI`的回复）、`tool`（工具消息，工具调用的结果）。普通对话模型主要支持纯文本内容。注意不能只包含系统消息或助手消息。
          items:
            oneOf:
              - title: 用户消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - user
                    description: 消息作者的角色
                    default: user
                  content:
                    type: string
                    description: 文本消息内容
                    example: >-
                      What opportunities and challenges will the Chinese large
                      model industry face in 2025?
                required:
                  - role
                  - content
              - title: 系统消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - system
                    description: 消息作者的角色
                    default: system
                  content:
                    type: string
                    description: 消息文本内容
                    example: You are a helpful assistant.
                required:
                  - role
                  - content
              - title: 助手消息
                type: object
                description: 可包含工具调用
                properties:
                  role:
                    type: string
                    enum:
                      - assistant
                    description: 消息作者的角色
                    default: assistant
                  content:
                    type: string
                    description: 文本消息内容
                    example: I'll help you with that analysis.
                  tool_calls:
                    type: array
                    description: 模型生成的工具调用消息。当提供此字段时，`content`通常为空。
                    items:
                      type: object
                      properties:
                        id:
                          type: string
                          description: 工具调用ID
                        type:
                          type: string
                          description: 工具类型，支持 `web_search、retrieval、function`
                          enum:
                            - function
                            - web_search
                            - retrieval
                        function:
                          type: object
                          description: 函数调用信息，当`type`为`function`时不为空
                          properties:
                            name:
                              type: string
                              description: 函数名称
                            arguments:
                              type: string
                              description: 函数参数，`JSON`格式字符串
                          required:
                            - name
                            - arguments
                      required:
                        - id
                        - type
                required:
                  - role
              - title: 工具消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - tool
                    description: 消息作者的角色
                    default: tool
                  content:
                    type: string
                    description: 消息文本内容
                    example: 'Function executed successfully with result: ...'
                  tool_call_id:
                    type: string
                    description: 指示此消息对应的工具调用 `ID`
                required:
                  - role
                  - content
          minItems: 1
        stream:
          type: boolean
          example: false
          default: false
          description: >-
            是否启用流式输出模式。默认值为 `false`。当设置为 `false`
            时，模型会在生成完整响应后一次性返回所有内容，适合短文本生成和批处理场景。当设置为 `true` 时，模型会通过`Server-Sent
            Events
            (SSE)`流式返回生成的内容，用户可以实时看到文本生成过程，适合聊天对话和长文本生成场景，能提供更好的用户体验。流式输出结束时会返回
            `data: [DONE]` 消息。
        thinking:
          $ref: '#/components/schemas/ChatThinking'
        do_sample:
          type: boolean
          example: true
          default: true
          description: >-
            是否启用采样策略来生成文本。默认值为 `true`。当设置为 `true` 时，模型会使用 `temperature、top_p`
            等参数进行随机采样，生成更多样化的输出；当设置为 `false` 时，模型总是选择概率最高的词汇，生成更确定性的输出，此时
            `temperature` 和 `top_p` 参数将被忽略。对于需要一致性和可重复性的任务（如代码生成、翻译），建议设置为
            `false`。
        temperature:
          type: number
          description: >-
            采样温度，控制输出的随机性和创造性，取值范围为 `[0.0, 1.0]`，限两位小数。对于`GLM-5.1` `GLM-5`
            `GLM-4.7` `GLM-4.6`系列默认值为 `1.0`，`GLM-4.5`系列默认值为 `0.6`，`GLM-4`系列默认值为
            `0.75`。较高的值（如`0.8`）会使输出更随机、更具创造性，适合创意写作和头脑风暴；较低的值（如`0.2`）会使输出更稳定、更确定，适合事实性问答和代码生成。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 1
          default: 1
          minimum: 0
          maximum: 1
        top_p:
          type: number
          description: >-
            核采样（`nucleus sampling`）参数，是`temperature`采样的替代方法，取值范围为 `[0.01,
            1.0]`，限两位小数。对于`GLM-5.1` `GLM-5` `GLM-4.7` `GLM-4.6` `GLM-4.5`系列默认值为
            `0.95`，`GLM-4`系列默认值为
            `0.9`。模型只考虑累积概率达到`top_p`的候选词汇。例如：`0.1`表示只考虑前`10%`概率的词汇，`0.9`表示考虑前`90%`概率的词汇。较小的值会产生更集中、更一致的输出；较大的值会增加输出的多样性。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不建议同时调整两个参数。
          format: float
          example: 0.95
          default: 0.95
          minimum: 0.01
          maximum: 1
        max_tokens:
          type: integer
          description: >-
            模型输出的最大令牌`token`数量限制。`GLM-5.1` `GLM-5` `GLM-4.7`
            `GLM-4.6`系列最大支持`128K`输出长度，`GLM-4.5`系列最大支持`96K`输出长度，建议设置不小于`1024`。令牌是文本的基本单位，通常`1`个令牌约等于`0.75`个英文单词或`1.5`个中文字符。设置合适的`max_tokens`可以控制响应长度和成本，避免过长的输出。如果模型在达到`max_tokens`限制前完成回答，会自然结束；如果达到限制，输出可能被截断。

            默认值和最大值等更多详见 [max_tokens
            文档](/cn/guide/start/concept-param#max_tokens)
          example: 1024
          minimum: 1
          maximum: 131072
        tool_stream:
          type: boolean
          example: false
          default: false
          description: >-
            是否开启流式响应`Function Calls`，仅限`GLM-5.1` `GLM-5` `GLM-5-Turbo` `GLM-4.7`
            `GLM-4.6`系列支持此参数，默认值`false`。参考
            [工具流式输出](/cn/guide/capabilities/stream-tool)
        tools:
          type: array
          description: >-
            模型可以调用的工具列表。支持函数调用、知识库检索和网络搜索。使用此参数提供模型可以生成 `JSON`
            输入的函数列表或配置其他工具。最多支持 `128` 个函数。目前 `GLM-4` 系列已支持所有 `tools`，`GLM-4.5`
            已支持 `web search` 和 `retrieval`。
          anyOf:
            - items:
                $ref: '#/components/schemas/FunctionToolSchema'
            - items:
                $ref: '#/components/schemas/RetrievalToolSchema'
            - items:
                $ref: '#/components/schemas/WebSearchToolSchema'
            - items:
                $ref: '#/components/schemas/MCPToolSchema'
        tool_choice:
          oneOf:
            - type: string
              enum:
                - auto
              description: 用于控制模型选择调用哪个函数的方式，仅在工具类型为`function`时补充。默认`auto`且仅支持`auto`。
          description: 控制模型如何选择工具。
        stop:
          type: array
          description: >-
            停止词列表，当模型生成的文本中遇到这些指定的字符串时会立即停止生成。目前仅支持单个停止词，格式为["stop_word1"]。停止词不会包含在返回的文本中。这对于控制输出格式、防止模型生成不需要的内容非常有用，例如在对话场景中可以设置["Human:"]来防止模型模拟用户发言。
          items:
            type: string
          maxItems: 1
        response_format:
          type: object
          description: >-
            指定模型的响应输出格式，默认为`text`，仅文本模型支持此字段。支持两种格式：{ "type": "text" }
            表示普通文本输出模式，模型返回自然语言文本；{ "type": "json_object" }
            表示`JSON`输出模式，模型会返回有效的`JSON`格式数据，适用于结构化数据提取、`API`响应生成等场景。使用`JSON`模式时，建议在提示词中明确说明需要`JSON`格式输出。
          properties:
            type:
              type: string
              enum:
                - text
                - json_object
              default: text
              description: 输出格式类型：`text`表示普通文本输出，`json_object`表示`JSON`格式输出
          required:
            - type
        request_id:
          type: string
          description: 请求唯一标识符。由用户端传递，建议使用`UUID`格式确保唯一性，若未提供平台将自动生成。
        user_id:
          type: string
          description: 终端用户的唯一标识符。`ID`长度要求：最少`6`个字符，最多`128`个字符，建议使用不包含敏感信息的唯一标识。
          minLength: 6
          maxLength: 128
    ChatCompletionVisionRequest:
      required:
        - model
        - messages
      type: object
      description: 视觉模型请求，支持多模态内容（文本、图片、视频、文件）
      properties:
        model:
          type: string
          description: >-
            调用的视觉模型代码。`GLM-5V-Turbo`
            系列支持视觉理解，具备卓越的多模态理解能力和工具调用能力。`AutoGLM-Phone` 是手机智能助理模型。
          example: glm-5v-turbo
          default: glm-5v-turbo
          enum:
            - glm-5v-turbo
            - glm-4.6v
            - autoglm-phone
            - glm-4.6v-flash
            - glm-4.6v-flashx
            - glm-4v-flash
            - glm-4.1v-thinking-flashx
            - glm-4.1v-thinking-flash
        messages:
          type: array
          description: >-
            对话消息列表，包含当前对话的完整上下文信息。每条消息都有特定的角色和内容，模型会根据这些消息生成回复。消息按时间顺序排列，支持角色：`system`（系统消息，用于设定`AI`的行为和角色）、`user`（用户消息，来自用户的输入）、`assistant`（助手消息，来自`AI`的回复）。视觉模型支持纯文本和多模态内容（文本、图片、视频、文件）。注意不能只包含系统或助手消息。
          items:
            oneOf:
              - title: 用户消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - user
                    description: 消息作者的角色
                    default: user
                  content:
                    oneOf:
                      - type: array
                        description: 多模态消息内容，支持文本、图片、文件、视频（可从上方切换至文本消息）
                        items:
                          $ref: '#/components/schemas/VisionMultimodalContentItem'
                      - type: string
                        description: 文本消息内容（可从上方切换至多模态消息）
                        example: >-
                          What opportunities and challenges will the Chinese
                          large model industry face in 2025?
                required:
                  - role
                  - content
              - title: 系统消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - system
                    description: 消息作者的角色
                    default: system
                  content:
                    oneOf:
                      - type: string
                        description: 消息文本内容
                        example: You are a helpful assistant.
                required:
                  - role
                  - content
              - title: 助手消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - assistant
                    description: 消息作者的角色
                    default: assistant
                  content:
                    oneOf:
                      - type: string
                        description: 文本消息内容
                        example: I'll help you with that analysis.
                required:
                  - role
          minItems: 1
        stream:
          type: boolean
          example: false
          default: false
          description: >-
            是否启用流式输出模式。默认值为 `false`。当设置为 `false`
            时，模型会在生成完整响应后一次性返回所有内容，适合短文本生成和批处理场景。当设置为 `true` 时，模型会通过`Server-Sent
            Events
            (SSE)`流式返回生成的内容，用户可以实时看到文本生成过程，适合聊天对话和长文本生成场景，能提供更好的用户体验。流式输出结束时会返回
            `data: [DONE]` 消息。
        thinking:
          $ref: '#/components/schemas/ChatThinking'
        do_sample:
          type: boolean
          example: true
          default: true
          description: >-
            是否启用采样策略来生成文本。默认值为 `true`。当设置为 `true` 时，模型会使用 `temperature、top_p`
            等参数进行随机采样，生成更多样化的输出；当设置为 `false` 时，模型总是选择概率最高的词汇，生成更确定性的输出，此时
            `temperature` 和 `top_p` 参数将被忽略。对于需要一致性和可重复性的任务（如代码生成、翻译），建议设置为
            `false`。
        temperature:
          type: number
          description: >-
            采样温度，控制输出的随机性和创造性，取值范围为 `[0.0,
            1.0]`，限两位小数。对于`GLM-5V-Turbo`，`GLM-4.6V`, `GLM-4.5V`系列默认值为
            `0.8`，`AutoGLM-Phone`默认值为 `0.0`，`GLM-4.1v`系列默认值为
            `0.8`。较高的值（如`0.8`）会使输出更随机、更具创造性，适合创意写作和头脑风暴；较低的值（如`0.2`）会使输出更稳定、更确定，适合事实性问答和代码生成。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.8
          default: 0.8
          minimum: 0
          maximum: 1
        top_p:
          type: number
          description: >-
            核采样（`nucleus sampling`）参数，是`temperature`采样的替代方法，取值范围为 `[0.01,
            1.0]`，限两位小数。对于`GLM-5V-Turbo`，`GLM-4.6V`, `GLM-4.5V`系列默认值为
            `0.6`，`AutoGLM-Phone`默认值为 `0.85`，`GLM-4.1v`系列默认值为
            `0.6`。模型只考虑累积概率达到`top_p`的候选词汇。例如：`0.1`表示只考虑前`10%`概率的词汇，`0.9`表示考虑前`90%`概率的词汇。较小的值会产生更集中、更一致的输出；较大的值会增加输出的多样性。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.6
          default: 0.6
          minimum: 0.01
          maximum: 1
        max_tokens:
          type: integer
          description: >-
            模型输出的最大令牌`token`数量限制。`GLM-5V-Turbo`最大支持`128K`输出长度，`GLM-4.6V`最大支持`32K`输出长度，`GLM-4.5V`最大支持`16K`输出长度，`AutoGLM-Phone`最大支持`4K`输出长度，`GLM-4.1v`系列最大支持`16K`输出长度，建议设置不小于`1024`。令牌是文本的基本单位，通常`1`个令牌约等于`0.75`个英文单词或`1.5`个中文字符。设置合适的`max_tokens`可以控制响应长度和成本，避免过长的输出。如果模型在达到`max_tokens`限制前完成回答，会自然结束；如果达到限制，输出可能被截断。

            默认值和最大值等更多详见 [max_tokens
            文档](/cn/guide/start/concept-param#max_tokens)
          example: 1024
          minimum: 1
          maximum: 131072
        tools:
          type: array
          description: >-
            模型可以调用的工具列表。仅限`GLM-4.6V`和`AutoGLM-Phone`支持。使用此参数提供模型可以生成 `JSON`
            输入的函数列表或配置其他工具。最多支持 `128` 个函数。
          anyOf:
            - items:
                $ref: '#/components/schemas/FunctionToolSchema'
        tool_choice:
          oneOf:
            - type: string
              enum:
                - auto
              description: >-
                用于控制模型选择调用哪个函数的方式，仅在工具类型为`function`时补充，仅限`GLM-4.6V`支持此参数。默认`auto`且仅支持`auto`。
          description: 控制模型如何选择工具。
        stop:
          type: array
          description: >-
            停止词列表，当模型生成的文本中遇到这些指定的字符串时会立即停止生成。目前仅支持单个停止词，格式为["stop_word1"]。停止词不会包含在返回的文本中。这对于控制输出格式、防止模型生成不需要的内容非常有用，例如在对话场景中可以设置["Human:"]来防止模型模拟用户发言。
          items:
            type: string
          maxItems: 1
        request_id:
          type: string
          description: 请求唯一标识符。由用户端传递，建议使用`UUID`格式确保唯一性，若未提供平台将自动生成。
        user_id:
          type: string
          description: 终端用户的唯一标识符。`ID`长度要求：最少`6`个字符，最多`128`个字符，建议使用不包含敏感信息的唯一标识。
          minLength: 6
          maxLength: 128
    ChatCompletionAudioRequest:
      required:
        - model
        - messages
      type: object
      description: 音频模型请求，支持语音理解、生成和识别功能
      properties:
        model:
          type: string
          description: 调用的音频模型代码。`GLM-4-Voice` 支持语音理解和生成。
          example: glm-4-voice
          default: glm-4-voice
          enum:
            - glm-4-voice
            - 禁用仅占位
        messages:
          type: array
          description: >-
            对话消息列表，包含当前对话的完整上下文信息。每条消息都有特定的角色和内容，模型会根据这些消息生成回复。消息按时间顺序排列，支持角色：`system`（系统消息，用于设定`AI`的行为和角色）、`user`（用户消息，来自用户的输入）、`assistant`（助手消息，来自`AI`的回复）。音频模型支持文本和音频内容。注意不能只包含系统或助手消息。
          items:
            oneOf:
              - title: 用户消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - user
                    description: 消息作者的角色
                    default: user
                  content:
                    oneOf:
                      - type: array
                        description: 多模态消息内容，支持文本、音频
                        items:
                          $ref: '#/components/schemas/AudioMultimodalContentItem'
                      - type: string
                        description: 消息文本内容
                        example: You are a helpful assistant.
                required:
                  - role
                  - content
              - title: 系统消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - system
                    description: 消息作者的角色
                    default: system
                  content:
                    type: string
                    description: 消息文本内容
                    example: 你是一个专业的语音助手，能够理解和生成自然语音。
                required:
                  - role
                  - content
              - title: 助手消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - assistant
                    description: 消息作者的角色
                    default: assistant
                  content:
                    oneOf:
                      - type: string
                        description: 文本消息内容
                        example: I'll help you with that analysis.
                  audio:
                    type: object
                    description: 语音消息
                    properties:
                      id:
                        type: string
                        description: 语音消息`id`，用于多轮对话
                required:
                  - role
          minItems: 1
        stream:
          type: boolean
          example: false
          default: false
          description: >-
            是否启用流式输出模式。默认值为 `false`。当设置为 `false`
            时，模型会在生成完整响应后一次性返回所有内容，适合语音识别和批处理场景。当设置为 `true` 时，模型会通过`Server-Sent
            Events
            (SSE)`流式返回生成的内容，用户可以实时看到文本生成过程，适合实时语音对话场景，能提供更好的用户体验。流式输出结束时会返回
            `data: [DONE]` 消息。
        do_sample:
          type: boolean
          example: true
          default: true
          description: >-
            是否启用采样策略来生成文本。默认值为 `true`。当设置为 `true` 时，模型会使用 `temperature、top_p`
            等参数进行随机采样，生成更多样化的输出；当设置为 `false` 时，模型总是选择概率最高的词汇，生成更确定性的输出，此时
            `temperature` 和 `top_p` 参数将被忽略。对于需要一致性和可重复性的任务（如语音识别、转录），建议设置为
            `false`。
        temperature:
          type: number
          description: >-
            采样温度，控制输出的随机性和创造性，取值范围为 `[0.0, 1.0]`，限两位小数。对于`GLM-4-Voice`默认值为
            `0.8`。较高的值（如`0.8`）会使输出更随机、更具创造性，适合语音生成和对话；较低的值（如`0.1`）会使输出更稳定、更确定，适合语音识别和转录。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.8
          default: 0.8
          minimum: 0
          maximum: 1
        top_p:
          type: number
          description: >-
            核采样（`nucleus sampling`）参数，是`temperature`采样的替代方法，取值范围为 `[0.01,
            1.0]`，限两位小数。对于`GLM-4-Voice`默认值为
            `0.6`。模型只考虑累积概率达到`top_p`的候选词汇。例如：`0.1`表示只考虑前`10%`概率的词汇，`0.9`表示考虑前`90%`概率的词汇。较小的值会产生更集中、更一致的输出；较大的值会增加输出的多样性。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.6
          default: 0.6
          minimum: 0.01
          maximum: 1
        max_tokens:
          type: integer
          description: 模型输出的最大令牌`token`数量限制。`GLM-4-Voice`最大支持`4K`输出长度，默认`1024`。令牌是文本的基本单位。
          example: 1024
          minimum: 1
          maximum: 4096
        watermark_enabled:
          type: boolean
          description: |-
            控制`AI`生成图片时是否添加水印。
             - `true`: 默认启用`AI`生成的显式水印及隐式数字水印，符合政策要求。
             - `false`: 关闭所有水印，仅允许已签署免责声明的客户使用，签署路径：个人中心-安全管理-去水印管理
          example: true
        stop:
          type: array
          description: >-
            停止词列表，当模型生成的文本中遇到这些指定的字符串时会立即停止生成。目前仅支持单个停止词，格式为["stop_word1"]。停止词不会包含在返回的文本中。这对于控制输出格式、防止模型生成不需要的内容非常有用。
          items:
            type: string
          maxItems: 1
        request_id:
          type: string
          description: 请求唯一标识符。由用户端传递，建议使用`UUID`格式确保唯一性，若未提供平台将自动生成。
        user_id:
          type: string
          description: 终端用户的唯一标识符。`ID`长度要求：最少`6`个字符，最多`128`个字符，建议使用不包含敏感信息的唯一标识。
          minLength: 6
          maxLength: 128
    ChatCompletionHumanOidRequest:
      required:
        - model
        - messages
      type: object
      description: 角色扮演，专业心理咨询专用模型
      properties:
        model:
          type: string
          description: 调用的专用模型代码。`CharGLM-4` 是角色扮演专用模型，`Emohaa` 是专业心理咨询模型。
          example: charglm-4
          default: charglm-4
          enum:
            - charglm-4
            - emohaa
        meta:
          type: object
          description: 角色及用户信息数据(仅限 `Emohaa` 支持此参数)
          required:
            - user_info
            - bot_info
            - bot_name
            - user_name
          properties:
            user_info:
              type: string
              description: 用户信息描述
            bot_info:
              type: string
              description: 角色信息描述
            bot_name:
              type: string
              description: 角色名称
            user_name:
              type: string
              description: 用户名称
        messages:
          type: array
          description: >-
            对话消息列表，包含当前对话的完整上下文信息。每条消息都有特定的角色和内容，模型会根据这些消息生成回复。消息按时间顺序排列，支持角色：`system`（系统消息，用于设定`AI`的行为和角色）、`user`（用户消息，来自用户的输入）、`assistant`（助手消息，来自`AI`的回复）。注意不能只包含系统消息或助手消息。
          items:
            oneOf:
              - title: 用户消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - user
                    description: 消息作者的角色
                    default: user
                  content:
                    type: string
                    description: 文本消息内容
                    example: 我最近工作压力很大，经常感到焦虑，不知道该怎么办
                required:
                  - role
                  - content
              - title: 系统消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - system
                    description: 消息作者的角色
                    default: system
                  content:
                    type: string
                    description: 消息文本内容
                    example: >-
                      你乃苏东坡。人生如梦，何不活得潇洒一些？在这忙碌纷繁的现代生活中，帮助大家找到那份属于自己的自在与豁达，共赏人生之美好
                required:
                  - role
                  - content
              - title: 助手消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - assistant
                    description: 消息作者的角色
                    default: assistant
                  content:
                    type: string
                    description: 文本消息内容
                    example: I'll help you with that analysis.
                required:
                  - role
                  - content
          minItems: 1
        stream:
          type: boolean
          example: false
          default: false
          description: >-
            是否启用流式输出模式。默认值为 `false`。当设置为 `fals`e
            时，模型会在生成完整响应后一次性返回所有内容，适合语音识别和批处理场景。当设置为 `true` 时，模型会通过`Server-Sent
            Events
            (SSE)`流式返回生成的内容，用户可以实时看到文本生成过程，适合实时语音对话场景，能提供更好的用户体验。流式输出结束时会返回
            `data: [DONE]` 消息。
        do_sample:
          type: boolean
          example: true
          default: true
          description: >-
            是否启用采样策略来生成文本。默认值为 `true`。当设置为 `true` 时，模型会使用 `temperature、top_p`
            等参数进行随机采样，生成更多样化的输出；当设置为 `false` 时，模型总是选择概率最高的词汇，生成更确定性的输出，此时
            `temperatur`e 和 `top_p` 参数将被忽略。对于需要一致性和可重复性的任务（如语音识别、转录），建议设置为
            `false`。
        temperature:
          type: number
          description: >-
            采样温度，控制输出的随机性和创造性，取值范围为 `[0.0, 1.0]`，限两位小数。`Charglm-4` 和 `Emohaa`
            默认值为 `0.95`。建议根据应用场景调整 `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.8
          default: 0.8
          minimum: 0
          maximum: 1
        top_p:
          type: number
          description: >-
            核采样（`nucleus sampling`）参数，是`temperature`采样的替代方法，取值范围为 `[0.01,
            1.0]`，限两位小数。`Charglm-4` 和 `Emohaa` 默认值为 `0.7`。建议根据应用场景调整 `top_p` 或
            `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.6
          default: 0.6
          minimum: 0.01
          maximum: 1
        max_tokens:
          type: integer
          description: >-
            模型输出的最大令牌`token`数量限制。`Charglm-4` 和 `Emohaa`
            最大支持`4K`输出长度，默认`1024`。令牌是文本的基本单位。
          example: 1024
          minimum: 1
          maximum: 4096
        stop:
          type: array
          description: >-
            停止词列表，当模型生成的文本中遇到这些指定的字符串时会立即停止生成。目前仅支持单个停止词，格式为["stop_word1"]。停止词不会包含在返回的文本中。这对于控制输出格式、防止模型生成不需要的内容非常有用。
          items:
            type: string
          maxItems: 1
        request_id:
          type: string
          description: 请求唯一标识符。由用户端传递，建议使用`UUID`格式确保唯一性，若未提供平台将自动生成。
        user_id:
          type: string
          description: 终端用户的唯一标识符。`ID`长度要求：最少`6`个字符，最多`128`个字符，建议使用不包含敏感信息的唯一标识。
          minLength: 6
          maxLength: 128
    ChatCompletionResponse:
      type: object
      properties:
        id:
          description: 任务 `ID`
          type: string
        request_id:
          description: 请求 `ID`
          type: string
        created:
          description: 请求创建时间，`Unix` 时间戳（秒）
          type: integer
        model:
          description: 模型名称
          type: string
        choices:
          type: array
          description: 模型响应列表
          items:
            type: object
            properties:
              index:
                type: integer
                description: 结果索引
              message:
                $ref: '#/components/schemas/ChatCompletionResponseMessage'
              finish_reason:
                type: string
                description: >-
                  推理终止原因。'stop’表示自然结束或触发stop词，'tool_calls’表示模型命中函数，'length’表示达到token长度限制，'sensitive’表示内容被安全审核接口拦截（用户应判断并决定是否撤回公开内容），'network_error’表示模型推理异常，'model_context_window_exceeded'表示超出模型上下文窗口。
        usage:
          type: object
          description: 调用结束时返回的 `Token` 使用统计。
          properties:
            prompt_tokens:
              type: number
              description: 用户输入的 `Token` 数量。
            completion_tokens:
              type: number
              description: 输出的 `Token` 数量
            prompt_tokens_details:
              type: object
              properties:
                cached_tokens:
                  type: number
                  description: 命中的缓存 `Token` 数量
            total_tokens:
              type: integer
              description: '`Token` 总数，对于 `glm-4-voice` 模型，`1`秒音频=`12.5 Tokens`，向上取整'
        video_result:
          type: array
          description: 视频生成结果。
          items:
            type: object
            properties:
              url:
                type: string
                description: 视频链接。
              cover_image_url:
                type: string
                description: 视频封面链接。
        web_search:
          type: array
          description: 返回与网页搜索相关的信息，使用`WebSearchToolSchema`时返回
          items:
            type: object
            properties:
              icon:
                type: string
                description: 来源网站的图标
              title:
                type: string
                description: 搜索结果的标题
              link:
                type: string
                description: 搜索结果的网页链接
              media:
                type: string
                description: 搜索结果网页的媒体来源名称
              publish_date:
                type: string
                description: 网站发布时间
              content:
                type: string
                description: 搜索结果网页引用的文本内容
              refer:
                type: string
                description: 角标序号
        content_filter:
          type: array
          description: 返回内容安全的相关信息
          items:
            type: object
            properties:
              role:
                type: string
                description: >-
                  安全生效环节，包括 `role = assistant` 模型推理，`role = user` 用户输入，`role =
                  history` 历史上下文
              level:
                type: integer
                description: 严重程度 `level 0-3`，`level 0`表示最严重，`3`表示轻微
    ChatCompletionChunk:
      type: object
      properties:
        id:
          type: string
          description: 任务 ID
        created:
          description: 请求创建时间，`Unix` 时间戳（秒）
          type: integer
        model:
          description: 模型名称
          type: string
        choices:
          type: array
          description: 模型响应列表
          items:
            type: object
            properties:
              index:
                type: integer
                description: 结果索引
              delta:
                type: object
                description: 模型增量返回的文本信息
                properties:
                  role:
                    type: string
                    description: 当前对话的角色，目前默认为 `assistant`（模型）
                  content:
                    oneOf:
                      - type: string
                        description: >-
                          当前对话文本内容。如果调用函数则为 `null`，否则返回推理结果。

                          对于`GLM-4.5V`系列模型，返回内容可能包含思考过程标签 `<think>
                          </think>`，文本边界标签 `<|begin_of_box|> <|end_of_box|>`。
                      - type: array
                        description: 当前对话的多模态内容（适用于`GLM-4V`系列）
                        items:
                          type: object
                          properties:
                            type:
                              type: string
                              enum:
                                - text
                              description: 内容类型，目前为文本
                            text:
                              type: string
                              description: 文本内容
                      - type: string
                        nullable: true
                        description: 当使用`tool_calls`时，`content`可能为`null`
                  audio:
                    type: object
                    description: 当使用 `glm-4-voice` 模型时返回的音频内容
                    properties:
                      id:
                        type: string
                        description: 当前对话的音频内容`id`，可用于多轮对话输入
                      data:
                        type: string
                        description: 当前对话的音频内容`base64`编码
                      expires_at:
                        type: string
                        description: 当前对话的音频内容过期时间
                  reasoning_content:
                    type: string
                    description: 思维链内容, 仅 `glm-4.5` 系列支持
                  tool_calls:
                    type: array
                    description: 生成的应该被调用的工具信息，流式返回时会逐步生成
                    items:
                      type: object
                      properties:
                        index:
                          type: integer
                          description: 工具调用索引
                        id:
                          type: string
                          description: 工具调用的唯一标识符
                        type:
                          type: string
                          description: 工具类型，目前支持`function`
                          enum:
                            - function
                        function:
                          type: object
                          properties:
                            name:
                              type: string
                              description: 函数名称
                            arguments:
                              type: string
                              description: 函数参数，`JSON`格式字符串
              finish_reason:
                type: string
                description: >-
                  模型推理终止的原因。`stop` 表示自然结束或触发stop词，`tool_calls` 表示模型命中函数，`length`
                  表示达到 `token` 长度限制，`sensitive`
                  表示内容被安全审核接口拦截（用户应判断并决定是否撤回公开内容），`network_error`
                  表示模型推理异常，'model_context_window_exceeded'表示超出模型上下文窗口。
                enum:
                  - stop
                  - length
                  - tool_calls
                  - sensitive
                  - network_error
        usage:
          type: object
          description: 本次模型调用的 `tokens` 数量统计
          properties:
            prompt_tokens:
              type: integer
              description: 用户输入的 `tokens` 数量。对于 `glm-4-voice`，`1`秒音频=`12.5 Tokens`，向上取整。
            completion_tokens:
              type: integer
              description: 模型输出的 `tokens` 数量
            total_tokens:
              type: integer
              description: 总 `tokens` 数量，对于 `glm-4-voice` 模型，`1`秒音频=`12.5 Tokens`，向上取整
        content_filter:
          type: array
          description: 返回内容安全的相关信息
          items:
            type: object
            properties:
              role:
                type: string
                description: >-
                  安全生效环节，包括：`role = assistant` 模型推理，`role = user` 用户输入，`role =
                  history` 历史上下文
              level:
                type: integer
                description: 严重程度 `level 0-3`，`level 0` 表示最严重，`3` 表示轻微
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
    ChatThinking:
      type: object
      description: 仅 `GLM-4.5` 及以上模型支持此参数配置. 控制大模型是否开启思维链。
      properties:
        type:
          type: string
          description: >-
            是否开启思维链(当开启后 `GLM-5.1` `GLM-5` `GLM-5-Turbo` `GLM-5v-Turbo`
            `GLM-4.7` `GLM-4.5V` 为强制思考，`GLM-4.6` `GLM-4.6V` `GLM-4.5`
            为模型自动判断是否思考), 默认: `enabled`.
          default: enabled
          enum:
            - enabled
            - disabled
        clear_thinking:
          type: boolean
          description: >-
            默认为 `True`。用于控制是否清除历史对话轮次（`previous turns`）中的 `reasoning_content`。详见
            [思考模式](/cn/guide/capabilities/thinking-mode) 
             - `true`（默认）：在本次请求中，系统会忽略/移除历史 `turns` 的 `reasoning_content`，仅使用非推理内容（如用户/助手可见文本、工具调用与结果等）作为上下文输入。适用于普通对话与轻量任务，可降低上下文长度与成本 
             - `false`：保留历史 `turns` 的 `reasoning_content` 并随上下文一同提供给模型。若你希望启用 `Preserved Thinking`，必须在 `messages` 中完整、未修改、按原顺序透传历史 `reasoning_content`；缺失、裁剪、改写或重排会导致效果下降或无法生效。
             - 注意：该参数只影响跨 `turn` 的历史 `thinking blocks`；不改变模型在当前 `turn` 内是否产生/输出 `thinking`
          default: true
          example: true
    FunctionToolSchema:
      type: object
      title: Function Call
      properties:
        type:
          type: string
          default: function
          enum:
            - function
        function:
          $ref: '#/components/schemas/FunctionObject'
      required:
        - type
        - function
      additionalProperties: false
    RetrievalToolSchema:
      type: object
      title: Retrieval
      properties:
        type:
          type: string
          default: retrieval
          enum:
            - retrieval
        retrieval:
          $ref: '#/components/schemas/RetrievalObject'
      required:
        - type
        - retrieval
      additionalProperties: false
    WebSearchToolSchema:
      type: object
      title: Web Search
      properties:
        type:
          type: string
          default: web_search
          enum:
            - web_search
        web_search:
          $ref: '#/components/schemas/WebSearchObject'
      required:
        - type
        - web_search
      additionalProperties: false
    MCPToolSchema:
      type: object
      title: MCP
      properties:
        type:
          type: string
          default: mcp
          enum:
            - mcp
        mcp:
          $ref: '#/components/schemas/MCPObject'
      required:
        - type
        - mcp
      additionalProperties: false
    VisionMultimodalContentItem:
      oneOf:
        - title: 文本
          type: object
          properties:
            type:
              type: string
              enum:
                - text
              description: 内容类型为文本
              default: text
            text:
              type: string
              description: 文本内容
          required:
            - type
            - text
          additionalProperties: false
        - title: 图片
          type: object
          properties:
            type:
              type: string
              enum:
                - image_url
              description: 内容类型为图片`URL`
              default: image_url
            image_url:
              type: object
              description: 图片信息
              properties:
                url:
                  type: string
                  description: >-
                    图片的`URL`地址或`Base64`编码。图像大小上传限制为每张图像`5M`以下，且像素不超过`6000*6000`。支持`jpg、png、jpeg`格式。`GLM-5V-Turbo`
                    `GLM4.6V` `GLM4.5V` 系列限制`50`张。`GLM-4V-Plus-0111`
                    限制`5`张。`GLM-4V-Flash`限制`1`张图像且不支持`Base64`编码。
              required:
                - url
              additionalProperties: false
          required:
            - type
            - image_url
          additionalProperties: false
        - title: 视频
          type: object
          properties:
            type:
              type: string
              enum:
                - video_url
              description: 内容类型为视频输入
              default: video_url
            video_url:
              type: object
              description: 视频信息。注意：`GLM-4V-Plus-0111` 的 `video_url` 参数必须在 `content` 数组的第一位。
              properties:
                url:
                  type: string
                  description: >-
                    视频的`URL`地址。`GLM-5V-Turbo` `GLM-4.6V` `GLM-4.5V` 系列视频大小限制为
                    `200M`
                    以内。`GLM-4V-Plus`视频大小限制为`20M`以内，视频时长不超过`30s`。对于其他多模态模型，视频大小限制为`200M`以内。视频类型：`mp4`，`mkv`，`mov`。
              required:
                - url
              additionalProperties: false
          required:
            - type
            - video_url
          additionalProperties: false
        - title: 文件
          type: object
          properties:
            type:
              type: string
              enum:
                - file_url
              description: >-
                内容类型为文件输入(仅`GLM-5V-Turbo` `GLM-4.6V` `GLM-4.5V`支持，且不支持同时传入
                `file_url` 和 `image_url` 或 `video_url` 参数)
              default: file_url
            file_url:
              type: object
              description: 文件信息。
              properties:
                url:
                  type: string
                  description: >-
                    文件的`URL`地址，不支持`Base64`编码。支持`pdf、txt、word、jsonl、xlsx、pptx`等格式，最多支持`50`个。
              required:
                - url
              additionalProperties: false
          required:
            - type
            - file_url
          additionalProperties: false
    AudioMultimodalContentItem:
      oneOf:
        - title: 文本
          type: object
          properties:
            type:
              type: string
              enum:
                - text
              description: 内容类型为文本
              default: text
            text:
              type: string
              description: 文本内容
          required:
            - type
            - text
          additionalProperties: false
        - title: 音频
          type: object
          properties:
            type:
              type: string
              enum:
                - input_audio
              description: 内容类型为音频输入
              default: input_audio
            input_audio:
              type: object
              description: 音频信息，仅`glm-4-voice`支持音频输入
              properties:
                data:
                  type: string
                  description: 语音文件的`base64`编码。音频最长不超过 `10` 分钟。`1s`音频=`12.5 Tokens`，向上取整。
                format:
                  type: string
                  description: 语音文件的格式，支持`wav`和`mp3`
                  enum:
                    - wav
                    - mp3
              required:
                - data
                - format
              additionalProperties: false
          required:
            - type
            - input_audio
          additionalProperties: false
    ChatCompletionResponseMessage:
      type: object
      properties:
        role:
          type: string
          description: 当前对话角色，默认为 `assistant`
          example: assistant
        content:
          oneOf:
            - type: string
              description: >-
                当前对话文本内容。如果调用函数则为 `null`，否则返回推理结果。

                对于`GLM-4.5V`系列模型，返回内容可能包含思考过程标签 `<think> </think>`，文本边界标签
                `<|begin_of_box|> <|end_of_box|>`。
            - type: array
              description: 多模态回复内容，适用于`GLM-4V`系列模型
              items:
                type: object
                properties:
                  type:
                    type: string
                    enum:
                      - text
                    description: 回复内容类型，目前为文本
                  text:
                    type: string
                    description: 文本内容
            - type: string
              nullable: true
              description: 当使用`tool_calls`时，`content`可能为`null`
        reasoning_content:
          type: string
          description: 思维链内容，仅在使用 `glm-4.5` 系列, `glm-4.1v-thinking` 系列模型时返回。
        audio:
          type: object
          description: 当使用 `glm-4-voice` 模型时返回的音频内容
          properties:
            id:
              type: string
              description: 当前对话的音频内容`id`，可用于多轮对话输入
            data:
              type: string
              description: 当前对话的音频内容`base64`编码
            expires_at:
              type: string
              description: 当前对话的音频内容过期时间
        tool_calls:
          type: array
          description: 生成的应该被调用的函数名称和参数。
          items:
            $ref: '#/components/schemas/ChatCompletionResponseMessageToolCall'
    FunctionObject:
      type: object
      properties:
        name:
          type: string
          description: 要调用的函数名称。必须是 `a-z、A-Z、0-9`，或包含下划线和破折号，最大长度为 `64`。
          minLength: 1
          maxLength: 64
          pattern: ^[a-zA-Z0-9_-]+$
        description:
          type: string
          description: 函数功能的描述，供模型选择何时以及如何调用函数。
        parameters:
          $ref: '#/components/schemas/FunctionParameters'
      required:
        - name
        - description
        - parameters
    RetrievalObject:
      type: object
      properties:
        knowledge_id:
          type: string
          description: 知识库 `ID`，从平台创建或获取
        prompt_template:
          type: string
          description: >-
            请求模型的提示模板，包含占位符 `{{ knowledge }}` 和 `{{ question }}`
            的自定义请求模板。默认模板：`在文档 `{{ knowledge }}` 中搜索问题 `{{question}}`
            的答案。如果找到答案，仅使用文档中的陈述进行回应；如果没有找到答案，使用你自己的知识回答并告知用户信息不来自文档。不要重复问题，直接开始答案。`
      required:
        - knowledge_id
    WebSearchObject:
      type: object
      properties:
        enable:
          type: boolean
          description: 是否启用搜索功能，默认值为 `false`，启用时设置为 `true`
        search_engine:
          type: string
          description: >-
            搜索引擎类型，默认为
            `search_std`；支持`search_std、search_pro、search_pro_sogou、search_pro_quark`。
          enum:
            - search_std
            - search_pro
            - search_pro_sogou
            - search_pro_quark
        search_query:
          type: string
          description: 强制触发搜索
        search_intent:
          type: string
          description: >-
            是否进行搜索意图识别，默认执行搜索意图识别。`true`：执行搜索意图识别，有搜索意图后执行搜索；`false`：跳过搜索意图识别，直接执行搜索
        count:
          type: integer
          description: >-
            返回结果的条数。可填范围：`1-50`，最大单次搜索返回`50`条，默认为`10`。支持的搜索引擎：`search_std、search_pro、search_pro_sogou`。对于`search_pro_sogou`:
            可选枚举值，`10、20、30、40、50`
          minimum: 1
          maximum: 50
        search_domain_filter:
          type: string
          description: |-
            用于限定搜索结果的范围，仅返回指定白名单域名的内容。
            白名单域名:（如 `www.example.com`）。
            支持的搜索引擎：`search_std、search_pro、search_pro_sogou`
        search_recency_filter:
          type: string
          description: >-
            搜索指定时间范围内的网页。默认为`noLimit`。可填值：`oneDay`（一天内）、`oneWeek`（一周内）、`oneMonth`（一个月内）、`oneYear`（一年内）、`noLimit`（不限，默认）。支持的搜索引擎：`search_std、search_pro、search_pro_sogou、search_pro_quark`
          enum:
            - oneDay
            - oneWeek
            - oneMonth
            - oneYear
            - noLimit
        content_size:
          type: string
          description: >-
            控制网页摘要的字数。默认值为`medium`。`medium`：返回摘要信息，满足大模型的基础推理需求。`high`：最大化上下文，信息量较大但内容详细，适合需要信息细节的场景。
          enum:
            - medium
            - high
        result_sequence:
          type: string
          description: 指定搜索结果返回的顺序是在模型回复结果之前还是之后，可选值：`before`、`after`，默认 `after`
          enum:
            - before
            - after
        search_result:
          type: boolean
          description: 是否返回搜索来源的详细信息，默认值 `false`
        require_search:
          type: boolean
          description: 是否强制搜索结果才返回回答，默认值 `false`
        search_prompt:
          type: string
          description: |-
            用于定制搜索结果处理的`Prompt`，默认`Prompt`：

            你是一位智能问答专家，具备整合信息的能力，能够进行时间识别、语义理解与矛盾信息清洗处理。
            当前日期是{{current_date}}，请以此时间为唯一基准，参考以下信息，全面、准确地回答用户问题。
            仅提炼有价值的内容用于回答，确保答案具有实时性与权威性，直接陈述答案，无需说明数据来源或内部处理过程。
      required:
        - search_engine
    MCPObject:
      type: object
      properties:
        server_label:
          description: >-
            `mcp server`标识，如果连接智谱的`mcp server`，以`mcp
            code`填充该字段，且无需填写`server_url`
          type: string
        server_url:
          description: '`mcp server`地址'
          type: string
        transport_type:
          description: 传输类型
          type: string
          default: streamable-http
          enum:
            - sse
            - streamable-http
        allowed_tools:
          description: 允许的工具集合
          type: array
          items:
            type: string
        headers:
          description: '`mcp server` 需要的鉴权信息'
          type: object
      required:
        - server_label
    ChatCompletionResponseMessageToolCall:
      type: object
      properties:
        function:
          type: object
          description: 包含生成的函数名称和 `JSON` 格式参数。
          properties:
            name:
              type: string
              description: 生成的函数名称。
            arguments:
              type: string
              description: 生成的函数调用参数的 `JSON` 格式字符串。调用函数前请验证参数。
          required:
            - name
            - arguments
        mcp:
          type: object
          description: '`MCP` 工具调用参数'
          properties:
            id:
              description: '`mcp` 工具调用唯一标识'
              type: string
            type:
              description: 工具调用类型, 例如 `mcp_list_tools, mcp_call`
              type: string
              enum:
                - mcp_list_tools
                - mcp_call
            server_label:
              description: '`MCP`服务器标签'
              type: string
            error:
              description: 错误信息
              type: string
            tools:
              description: '`type = mcp_list_tools` 时的工具列表'
              type: array
              items:
                type: object
                properties:
                  name:
                    description: 工具名称
                    type: string
                  description:
                    description: 工具描述
                    type: string
                  annotations:
                    description: 工具注解
                    type: object
                  input_schema:
                    description: 工具输入参数规范
                    type: object
                    properties:
                      type:
                        description: 固定值 'object'
                        type: string
                        default: object
                        enum:
                          - object
                      properties:
                        description: 参数属性定义
                        type: object
                      required:
                        description: 必填属性列表
                        type: array
                        items:
                          type: string
                      additionalProperties:
                        description: 是否允许额外参数
                        type: boolean
            arguments:
              description: 工具调用参数，参数为 `json` 字符串
              type: string
            name:
              description: 工具名称
              type: string
            output:
              description: 工具返回的结果输出
              type: object
        id:
          type: string
          description: 命中函数的唯一标识符。
        type:
          type: string
          description: 调用的工具类型，目前仅支持 'function', 'mcp'。
    FunctionParameters:
      type: object
      description: 使用 `JSON Schema` 定义的参数。必须传递 `JSON Schema` 对象以准确定义接受的参数。如果调用函数时不需要参数，则省略。
      additionalProperties: true
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````


> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 对话补全(异步)

> 和 [指定模型](/cn/guide/start/model-overview) 对话，通过查询异步结果获取模型响应。支持多种模型，支持多模态（文本、图片、音频、视频、文件），可配置采样，温度，最大令牌数，工具调用等。注意此为异步接口，通过 [查询异步结果](/api-reference/%E6%A8%A1%E5%9E%8B-api/%E6%9F%A5%E8%AF%A2%E5%BC%82%E6%AD%A5%E7%BB%93%E6%9E%9C) 获取生成结果。



## OpenAPI

````yaml /openapi/openapi.json post /paas/v4/async/chat/completions
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/async/chat/completions:
    post:
      tags:
        - 模型 API
      summary: 对话补全(异步)
      description: >-
        和 [指定模型](/cn/guide/start/model-overview)
        对话，通过查询异步结果获取模型响应。支持多种模型，支持多模态（文本、图片、音频、视频、文件），可配置采样，温度，最大令牌数，工具调用等。注意此为异步接口，通过
        [查询异步结果](/api-reference/%E6%A8%A1%E5%9E%8B-api/%E6%9F%A5%E8%AF%A2%E5%BC%82%E6%AD%A5%E7%BB%93%E6%9E%9C)
        获取生成结果。
      requestBody:
        content:
          application/json:
            schema:
              oneOf:
                - $ref: '#/components/schemas/AsyncChatCompletionTextRequest'
                  title: 文本模型
                - $ref: '#/components/schemas/AsyncChatCompletionVisionRequest'
                  title: 视觉模型
                - $ref: '#/components/schemas/AsyncChatCompletionAudioRequest'
                  title: 音频模型
                - $ref: '#/components/schemas/AsyncChatCompletionHumanOidRequest'
                  title: 角色模型
            examples:
              基础调用示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: system
                      content: 你是一个有用的AI助手。
                    - role: user
                      content: 请介绍一下人工智能的发展历程。
                  temperature: 1
              深度思考示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: user
                      content: 写一首关于春天的诗。
                  thinking:
                    type: enabled
              多轮对话示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: system
                      content: 你是一个专业的编程助手
                    - role: user
                      content: 什么是递归？
                    - role: assistant
                      content: 递归是一种编程技术，函数调用自身来解决问题...
                    - role: user
                      content: 能给我一个 Python 递归的例子吗？
              图片理解示例:
                value:
                  model: glm-5v-turbo
                  messages:
                    - role: user
                      content:
                        - type: image_url
                          image_url:
                            url: https://cdn.bigmodel.cn/static/logo/register.png
                        - type: image_url
                          image_url:
                            url: https://cdn.bigmodel.cn/static/logo/api-key.png
                        - type: text
                          text: What are the pics talk about?
              视频理解示例:
                value:
                  model: glm-5v-turbo
                  messages:
                    - role: user
                      content:
                        - type: video_url
                          video_url:
                            url: >-
                              https://cdn.bigmodel.cn/agent-demos/lark/113123.mov
                        - type: text
                          text: What are the video show about?
              文件理解示例:
                value:
                  model: glm-5v-turbo
                  messages:
                    - role: user
                      content:
                        - type: file_url
                          file_url:
                            url: https://cdn.bigmodel.cn/static/demo/demo2.txt
                        - type: file_url
                          file_url:
                            url: https://cdn.bigmodel.cn/static/demo/demo1.pdf
                        - type: text
                          text: What are the files show about?
              音频对话示例:
                value:
                  model: glm-4-voice
                  messages:
                    - role: user
                      content:
                        - type: text
                          text: 你好，这是我的语音输入测试，请慢速复述一遍
                        - type: input_audio
                          input_audio:
                            data: base64_voice_xxx
                            format: wav
              Function Call 示例:
                value:
                  model: glm-5.1
                  messages:
                    - role: user
                      content: 今天北京的天气怎么样？
                  tools:
                    - type: function
                      function:
                        name: get_weather
                        description: 获取指定城市的天气信息
                        parameters:
                          type: object
                          properties:
                            city:
                              type: string
                              description: 城市名称
                          required:
                            - city
                  tool_choice: auto
                  temperature: 0.3
        required: true
      responses:
        '200':
          description: 业务处理成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/AsyncResponse'
        default:
          description: 请求失败
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    AsyncChatCompletionTextRequest:
      required:
        - model
        - messages
      type: object
      description: 普通对话模型请求，支持纯文本对话和工具调用
      properties:
        model:
          type: string
          description: >-
            调用的普通对话模型代码。`GLM-5.1` 是最新的旗舰模型系列。`GLM-5`
            系列提供了复杂推理、超长上下文、极快推理速度等多款模型。
          example: glm-5.1
          default: glm-5.1
          enum:
            - glm-5.1
            - glm-5-turbo
            - glm-5
            - glm-4.7
            - glm-4.6
            - glm-4.5-air
            - glm-4.5-airx
            - glm-4.5-flash
            - glm-4-flash-250414
            - glm-4-flashx-250414
        messages:
          type: array
          description: >-
            对话消息列表，包含当前对话的完整上下文信息。每条消息都有特定的角色和内容，模型会根据这些消息生成回复。消息按时间顺序排列，支持四种角色：`system`（系统消息，用于设定`AI`的行为和角色）、`user`（用户消息，来自用户的输入）、`assistant`（助手消息，来自`AI`的回复）、`tool`（工具消息，工具调用的结果）。普通对话模型主要支持纯文本内容。注意不能只包含系统消息或助手消息。
          items:
            oneOf:
              - title: 用户消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - user
                    description: 消息作者的角色
                    default: user
                  content:
                    type: string
                    description: 文本消息内容
                    example: >-
                      What opportunities and challenges will the Chinese large
                      model industry face in 2025?
                required:
                  - role
                  - content
              - title: 系统消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - system
                    description: 消息作者的角色
                    default: system
                  content:
                    type: string
                    description: 消息文本内容
                    example: You are a helpful assistant.
                required:
                  - role
                  - content
              - title: 助手消息
                type: object
                description: 可包含工具调用
                properties:
                  role:
                    type: string
                    enum:
                      - assistant
                    description: 消息作者的角色
                    default: assistant
                  content:
                    type: string
                    description: 文本消息内容
                    example: I'll help you with that analysis.
                  tool_calls:
                    type: array
                    description: 模型生成的工具调用消息。当提供此字段时，`content`通常为空。
                    items:
                      type: object
                      properties:
                        id:
                          type: string
                          description: 工具调用ID
                        type:
                          type: string
                          description: 工具类型，支持 `web_search、retrieval、function`
                          enum:
                            - function
                            - web_search
                            - retrieval
                        function:
                          type: object
                          description: 函数调用信息，当`type`为`function`时不为空
                          properties:
                            name:
                              type: string
                              description: 函数名称
                            arguments:
                              type: string
                              description: 函数参数，`JSON`格式字符串
                          required:
                            - name
                            - arguments
                      required:
                        - id
                        - type
                required:
                  - role
              - title: 工具消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - tool
                    description: 消息作者的角色
                    default: tool
                  content:
                    type: string
                    description: 消息文本内容
                    example: 'Function executed successfully with result: ...'
                  tool_call_id:
                    type: string
                    description: 指示此消息对应的工具调用 `ID`
                required:
                  - role
                  - content
          minItems: 1
        thinking:
          $ref: '#/components/schemas/ChatThinking'
        do_sample:
          type: boolean
          example: true
          default: true
          description: >-
            是否启用采样策略来生成文本。默认值为 `true`。当设置为 `true` 时，模型会使用 `temperature、top_p`
            等参数进行随机采样，生成更多样化的输出；当设置为 `false` 时，模型总是选择概率最高的词汇，生成更确定性的输出，此时
            `temperature` 和 `top_p` 参数将被忽略。对于需要一致性和可重复性的任务（如代码生成、翻译），建议设置为
            `false`。
        temperature:
          type: number
          description: >-
            采样温度，控制输出的随机性和创造性，取值范围为 `(0.0, 1.0]`，限两位小数。对于`GLM-5.1` `GLM-5`
            `GLM-5-Turbo` `GLM-4.7` `GLM-4.6`系列默认值为 `1.0`，`GLM-4.5`系列默认值为
            `0.6`，`GLM-4`系列默认值为
            `0.75`。较高的值（如`0.8`）会使输出更随机、更具创造性，适合创意写作和头脑风暴；较低的值（如`0.2`）会使输出更稳定、更确定，适合事实性问答和代码生成。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 1
          default: 1
          minimum: 0
          maximum: 1
        top_p:
          type: number
          description: >-
            核采样（`nucleus sampling`）参数，是`temperature`采样的替代方法，取值范围为 `[0.01,
            1.0]`，限两位小数。对于`GLM-5.1` `GLM-5` `GLM-5-Turbo` `GLM-4.7` `GLM-4.6`
            `GLM-4.5`系列默认值为 `0.95`，`GLM-4`系列默认值为
            `0.9`。模型只考虑累积概率达到`top_p`的候选词汇。例如：`0.1`表示只考虑前`10%`概率的词汇，`0.9`表示考虑前`90%`概率的词汇。较小的值会产生更集中、更一致的输出；较大的值会增加输出的多样性。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不建议同时调整两个参数。
          format: float
          example: 0.95
          default: 0.95
          minimum: 0.01
          maximum: 1
        max_tokens:
          type: integer
          description: >-
            模型输出的最大令牌`token`数量限制。`GLM-5.1` `GLM-5` `GLM-5-Turbo` `GLM-4.7`
            `GLM-4.6`系列最大支持`128K`输出长度，`GLM-4.5`最大支持`96K`输出长度，建议设置不小于`1024`。令牌是文本的基本单位，通常`1`个令牌约等于`0.75`个英文单词或`1.5`个中文字符。设置合适的`max_tokens`可以控制响应长度和成本，避免过长的输出。如果模型在达到`max_tokens`限制前完成回答，会自然结束；如果达到限制，输出可能被截断。

            默认值和最大值等更多详见 [max_tokens
            文档](/cn/guide/start/concept-param#max_tokens)
          example: 1024
          minimum: 1
          maximum: 131072
        tools:
          type: array
          description: >-
            模型可以调用的工具列表。支持函数调用、知识库检索和网络搜索。使用此参数提供模型可以生成 `JSON`
            输入的函数列表或配置其他工具。最多支持 `128` 个函数。目前 `GLM-4` 系列已支持所有 `tools`，`GLM-4.5`
            已支持 `web search` 和 `retrieval`。
          anyOf:
            - items:
                $ref: '#/components/schemas/FunctionToolSchema'
            - items:
                $ref: '#/components/schemas/RetrievalToolSchema'
            - items:
                $ref: '#/components/schemas/WebSearchToolSchema'
            - items:
                $ref: '#/components/schemas/MCPToolSchema'
        tool_choice:
          oneOf:
            - type: string
              enum:
                - auto
              description: 用于控制模型选择调用哪个函数的方式，仅在工具类型为`function`时补充。默认`auto`且仅支持`auto`。
          description: 控制模型如何选择工具。
        stop:
          type: array
          description: >-
            停止词列表，当模型生成的文本中遇到这些指定的字符串时会立即停止生成。目前仅支持单个停止词，格式为["stop_word1"]。停止词不会包含在返回的文本中。这对于控制输出格式、防止模型生成不需要的内容非常有用，例如在对话场景中可以设置["Human:"]来防止模型模拟用户发言。
          items:
            type: string
          maxItems: 1
        response_format:
          type: object
          description: >-
            指定模型的响应输出格式，默认为`text`，仅文本模型支持此字段。支持两种格式：{ "type": "text" }
            表示普通文本输出模式，模型返回自然语言文本；{ "type": "json_object" }
            表示`JSON`输出模式，模型会返回有效的`JSON`格式数据，适用于结构化数据提取、`API`响应生成等场景。使用`JSON`模式时，建议在提示词中明确说明需要`JSON`格式输出。
          properties:
            type:
              type: string
              enum:
                - text
                - json_object
              default: text
              description: 输出格式类型：`text`表示普通文本输出，`json_object`表示`JSON`格式输出
          required:
            - type
        request_id:
          type: string
          description: 请求唯一标识符。由用户端传递，建议使用`UUID`格式确保唯一性，若未提供平台将自动生成。
        user_id:
          type: string
          description: 终端用户的唯一标识符。`ID`长度要求：最少`6`个字符，最多`128`个字符，建议使用不包含敏感信息的唯一标识。
          minLength: 6
          maxLength: 128
    AsyncChatCompletionVisionRequest:
      required:
        - model
        - messages
      type: object
      description: 视觉模型请求，支持多模态内容（文本、图片、视频、文件）
      properties:
        model:
          type: string
          description: 调用的视觉模型代码。`GLM-5V-Turbo` 系列支持视觉理解，具备卓越的多模态理解能力和工具调用能力。
          example: glm-5v-turbo
          default: glm-5v-turbo
          enum:
            - glm-5v-turbo
            - glm-4.6v
            - glm-4.6v-flash
            - glm-4.6v-flashx
            - glm-4v-flash
            - glm-4.1v-thinking-flashx
            - glm-4.1v-thinking-flash
        messages:
          type: array
          description: >-
            对话消息列表，包含当前对话的完整上下文信息。每条消息都有特定的角色和内容，模型会根据这些消息生成回复。消息按时间顺序排列，支持角色：`system`（系统消息，用于设定`AI`的行为和角色）、`user`（用户消息，来自用户的输入）、`assistant`（助手消息，来自`AI`的回复）。视觉模型支持纯文本和多模态内容（文本、图片、视频、文件）。注意不能只包含系统或助手消息。
          items:
            oneOf:
              - title: 用户消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - user
                    description: 消息作者的角色
                    default: user
                  content:
                    oneOf:
                      - type: array
                        description: 多模态消息内容，支持文本、图片、文件、视频（可从上方切换至文本消息）
                        items:
                          $ref: '#/components/schemas/VisionMultimodalContentItem'
                      - type: string
                        description: 文本消息内容（可从上方切换至多模态消息）
                        example: >-
                          What opportunities and challenges will the Chinese
                          large model industry face in 2025?
                required:
                  - role
                  - content
              - title: 系统消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - system
                    description: 消息作者的角色
                    default: system
                  content:
                    oneOf:
                      - type: string
                        description: 消息文本内容
                        example: You are a helpful assistant.
                required:
                  - role
                  - content
              - title: 助手消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - assistant
                    description: 消息作者的角色
                    default: assistant
                  content:
                    oneOf:
                      - type: string
                        description: 文本消息内容
                        example: I'll help you with that analysis.
                required:
                  - role
          minItems: 1
        thinking:
          $ref: '#/components/schemas/ChatThinking'
        do_sample:
          type: boolean
          example: true
          default: true
          description: >-
            是否启用采样策略来生成文本。默认值为 `true`。当设置为 `true` 时，模型会使用 `temperature、top_p`
            等参数进行随机采样，生成更多样化的输出；当设置为 `false` 时，模型总是选择概率最高的词汇，生成更确定性的输出，此时
            `temperature` 和 `top_p` 参数将被忽略。对于需要一致性和可重复性的任务（如代码生成、翻译），建议设置为
            `false`。
        temperature:
          type: number
          description: >-
            采样温度，控制输出的随机性和创造性，取值范围为 `[0.0,
            1.0]`，限两位小数。对于`GLM-5V-Turbo`，`GLM-4.6V`，`GLM-4.5V`系列默认值为
            `0.8`，`GLM-4.1v`系列默认值为
            `0.8`。较高的值（如`0.8`）会使输出更随机、更具创造性，适合创意写作和头脑风暴；较低的值（如`0.2`）会使输出更稳定、更确定，适合事实性问答和代码生成。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.8
          default: 0.8
          minimum: 0
          maximum: 1
        top_p:
          type: number
          description: >-
            核采样（`nucleus sampling`）参数，是`temperature`采样的替代方法，取值范围为 `[0.01,
            1.0]`，限两位小数。对于`GLM-5V-Turbo`，`GLM-4.6V`，`GLM-4.5V`系列默认值为
            `0.6`，`GLM-4.1v`系列默认值为
            `0.6`。模型只考虑累积概率达到`top_p`的候选词汇。例如：`0.1`表示只考虑前`10%`概率的词汇，`0.9`表示考虑前`90%`概率的词汇。较小的值会产生更集中、更一致的输出；较大的值会增加输出的多样性。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.6
          default: 0.6
          minimum: 0.01
          maximum: 1
        max_tokens:
          type: integer
          description: >-
            模型输出的最大令牌`token`数量限制。`GLM-5V-Turbo`最大支持`128K`输出长度，`GLM-4.6V`最大支持`32K`输出长度，`GLM-4.5V`最大支持`16K`输出长度，`GLM-4.1v`系列最大支持`16K`输出长度，建议设置不小于`1024`。令牌是文本的基本单位，通常`1`个令牌约等于`0.75`个英文单词或`1.5`个中文字符。设置合适的`max_tokens`可以控制响应长度和成本，避免过长的输出。如果模型在达到`max_tokens`限制前完成回答，会自然结束；如果达到限制，输出可能被截断。

            默认值和最大值等更多详见 [max_tokens
            文档](/cn/guide/start/concept-param#max_tokens)
          example: 1024
          minimum: 1
          maximum: 131072
        tools:
          type: array
          description: >-
            模型可以调用的工具列表。仅限`GLM-5V-Turbo`, `GLM-4.6V`支持。使用此参数提供模型可以生成 `JSON`
            输入的函数列表或配置其他工具。最多支持 `128` 个函数。
          anyOf:
            - items:
                $ref: '#/components/schemas/FunctionToolSchema'
        tool_choice:
          oneOf:
            - type: string
              enum:
                - auto
              description: 用于控制模型选择调用哪个函数的方式，仅在工具类型为`function`时补充。默认`auto`且仅支持`auto`。
          description: 控制模型如何选择工具。
        stop:
          type: array
          description: >-
            停止词列表，当模型生成的文本中遇到这些指定的字符串时会立即停止生成。目前仅支持单个停止词，格式为["stop_word1"]。停止词不会包含在返回的文本中。这对于控制输出格式、防止模型生成不需要的内容非常有用，例如在对话场景中可以设置["Human:"]来防止模型模拟用户发言。
          items:
            type: string
          maxItems: 1
        request_id:
          type: string
          description: 请求唯一标识符。由用户端传递，建议使用`UUID`格式确保唯一性，若未提供平台将自动生成。
        user_id:
          type: string
          description: 终端用户的唯一标识符。`ID`长度要求：最少`6`个字符，最多`128`个字符，建议使用不包含敏感信息的唯一标识。
          minLength: 6
          maxLength: 128
    AsyncChatCompletionAudioRequest:
      required:
        - model
        - messages
      type: object
      description: 音频模型请求，支持语音理解、生成和识别功能
      properties:
        model:
          type: string
          description: 调用的音频模型代码。`GLM-4-Voice` 支持语音理解和生成。
          example: glm-4-voice
          default: glm-4-voice
          enum:
            - glm-4-voice
            - 禁用仅占位
        messages:
          type: array
          description: >-
            对话消息列表，包含当前对话的完整上下文信息。每条消息都有特定的角色和内容，模型会根据这些消息生成回复。消息按时间顺序排列，支持角色：`system`（系统消息，用于设定`AI`的行为和角色）、`user`（用户消息，来自用户的输入）、`assistant`（助手消息，来自`AI`的回复）。音频模型支持文本和音频内容。注意不能只包含系统或助手消息。
          items:
            oneOf:
              - title: 用户消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - user
                    description: 消息作者的角色
                    default: user
                  content:
                    oneOf:
                      - type: array
                        description: 多模态消息内容，支持文本、音频
                        items:
                          $ref: '#/components/schemas/AudioMultimodalContentItem'
                      - type: string
                        description: 消息文本内容
                        example: You are a helpful assistant.
                required:
                  - role
                  - content
              - title: 系统消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - system
                    description: 消息作者的角色
                    default: system
                  content:
                    type: string
                    description: 消息文本内容
                    example: 你是一个专业的语音助手，能够理解和生成自然语音。
                required:
                  - role
                  - content
              - title: 助手消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - assistant
                    description: 消息作者的角色
                    default: assistant
                  content:
                    oneOf:
                      - type: string
                        description: 文本消息内容
                        example: I'll help you with that analysis.
                  audio:
                    type: object
                    description: 语音消息
                    properties:
                      id:
                        type: string
                        description: 语音消息`id`，用于多轮对话
                required:
                  - role
          minItems: 1
        do_sample:
          type: boolean
          example: true
          default: true
          description: >-
            是否启用采样策略来生成文本。默认值为 `true`。当设置为 `true` 时，模型会使用 `temperature、top_p`
            等参数进行随机采样，生成更多样化的输出；当设置为 `false` 时，模型总是选择概率最高的词汇，生成更确定性的输出，此时
            `temperature` 和 `top_p` 参数将被忽略。对于需要一致性和可重复性的任务（如语音识别、转录），建议设置为
            `false`。
        temperature:
          type: number
          description: >-
            采样温度，控制输出的随机性和创造性，取值范围为 `[0.0, 1.0]`，限两位小数。对于`GLM-4-Voice`默认值为
            `0.8`。较高的值（如`0.8`）会使输出更随机、更具创造性，适合语音生成和对话；较低的值（如`0.1`）会使输出更稳定、更确定，适合语音识别和转录。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.8
          default: 0.8
          minimum: 0
          maximum: 1
        top_p:
          type: number
          description: >-
            核采样（`nucleus sampling`）参数，是`temperature`采样的替代方法，取值范围为 `[0.01,
            1.0]`，限两位小数。对于`GLM-4-Voice`默认值为
            `0.6`。模型只考虑累积概率达到`top_p`的候选词汇。例如：`0.1`表示只考虑前`10%`概率的词汇，`0.9`表示考虑前`90%`概率的词汇。较小的值会产生更集中、更一致的输出；较大的值会增加输出的多样性。建议根据应用场景调整
            `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.6
          default: 0.6
          minimum: 0.01
          maximum: 1
        max_tokens:
          type: integer
          description: 模型输出的最大令牌`token`数量限制。`GLM-4-Voice`最大支持`4K`输出长度，默认`1024`。令牌是文本的基本单位。
          example: 1024
          minimum: 1
          maximum: 4096
        watermark_enabled:
          type: boolean
          description: |-
            控制`AI`生成图片时是否添加水印。
             - `true`: 默认启用`AI`生成的显式水印及隐式数字水印，符合政策要求。
             - `false`: 关闭所有水印，仅允许已签署免责声明的客户使用，签署路径：个人中心-安全管理-去水印管理
          example: true
        stop:
          type: array
          description: >-
            停止词列表，当模型生成的文本中遇到这些指定的字符串时会立即停止生成。目前仅支持单个停止词，格式为["stop_word1"]。停止词不会包含在返回的文本中。这对于控制输出格式、防止模型生成不需要的内容非常有用。
          items:
            type: string
          maxItems: 1
        request_id:
          type: string
          description: 请求唯一标识符。由用户端传递，建议使用`UUID`格式确保唯一性，若未提供平台将自动生成。
        user_id:
          type: string
          description: 终端用户的唯一标识符。`ID`长度要求：最少`6`个字符，最多`128`个字符，建议使用不包含敏感信息的唯一标识。
          minLength: 6
          maxLength: 128
    AsyncChatCompletionHumanOidRequest:
      required:
        - model
        - messages
      type: object
      description: 角色扮演，专业心理咨询专用模型
      properties:
        model:
          type: string
          description: 调用的专用模型代码。`CharGLM-4` 是角色扮演专用模型，`Emohaa` 是专业心理咨询模型。
          example: charglm-4
          default: charglm-4
          enum:
            - charglm-4
            - emohaa
        meta:
          type: object
          description: 角色及用户信息数据(仅限 `Emohaa` 支持此参数)
          required:
            - user_info
            - bot_info
            - bot_name
            - user_name
          properties:
            user_info:
              type: string
              description: 用户信息描述
            bot_info:
              type: string
              description: 角色信息描述
            bot_name:
              type: string
              description: 角色名称
            user_name:
              type: string
              description: 用户名称
        messages:
          type: array
          description: >-
            对话消息列表，包含当前对话的完整上下文信息。每条消息都有特定的角色和内容，模型会根据这些消息生成回复。消息按时间顺序排列，支持角色：`system`（系统消息，用于设定`AI`的行为和角色）、`user`（用户消息，来自用户的输入）、`assistant`（助手消息，来自`AI`的回复）。注意不能只包含系统消息或助手消息。
          items:
            oneOf:
              - title: 用户消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - user
                    description: 消息作者的角色
                    default: user
                  content:
                    type: string
                    description: 文本消息内容
                    example: 我最近工作压力很大，经常感到焦虑，不知道该怎么办
                required:
                  - role
                  - content
              - title: 系统消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - system
                    description: 消息作者的角色
                    default: system
                  content:
                    type: string
                    description: 消息文本内容
                    example: >-
                      你乃苏东坡。人生如梦，何不活得潇洒一些？在这忙碌纷繁的现代生活中，帮助大家找到那份属于自己的自在与豁达，共赏人生之美好
                required:
                  - role
                  - content
              - title: 助手消息
                type: object
                properties:
                  role:
                    type: string
                    enum:
                      - assistant
                    description: 消息作者的角色
                    default: assistant
                  content:
                    type: string
                    description: 文本消息内容
                    example: I'll help you with that analysis.
                required:
                  - role
                  - content
          minItems: 1
        do_sample:
          type: boolean
          example: true
          default: true
          description: >-
            是否启用采样策略来生成文本。默认值为 `true`。当设置为 `true` 时，模型会使用 `temperature、top_p`
            等参数进行随机采样，生成更多样化的输出；当设置为 `false` 时，模型总是选择概率最高的词汇，生成更确定性的输出，此时
            `temperatur`e 和 `top_p` 参数将被忽略。对于需要一致性和可重复性的任务（如语音识别、转录），建议设置为
            `false`。
        temperature:
          type: number
          description: >-
            采样温度，控制输出的随机性和创造性，取值范围为 `[0.0, 1.0]`，限两位小数。`Charglm-4` 和 `Emohaa`
            默认值为 `0.95`。建议根据应用场景调整 `top_p` 或 `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.8
          default: 0.8
          minimum: 0
          maximum: 1
        top_p:
          type: number
          description: >-
            核采样（`nucleus sampling`）参数，是`temperature`采样的替代方法，取值范围为 `[0.01,
            1.0]`，限两位小数。`Charglm-4` 和 `Emohaa` 默认值为 `0.7`。建议根据应用场景调整 `top_p` 或
            `temperature` 参数，但不要同时调整两个参数。
          format: float
          example: 0.6
          default: 0.6
          minimum: 0.01
          maximum: 1
        max_tokens:
          type: integer
          description: >-
            模型输出的最大令牌`token`数量限制。`Charglm-4` 和 `Emohaa`
            最大支持`4K`输出长度，默认`1024`。令牌是文本的基本单位。
          example: 1024
          minimum: 1
          maximum: 4096
        stop:
          type: array
          description: >-
            停止词列表，当模型生成的文本中遇到这些指定的字符串时会立即停止生成。目前仅支持单个停止词，格式为["stop_word1"]。停止词不会包含在返回的文本中。这对于控制输出格式、防止模型生成不需要的内容非常有用。
          items:
            type: string
          maxItems: 1
        request_id:
          type: string
          description: 请求唯一标识符。由用户端传递，建议使用`UUID`格式确保唯一性，若未提供平台将自动生成。
        user_id:
          type: string
          description: 终端用户的唯一标识符。`ID`长度要求：最少`6`个字符，最多`128`个字符，建议使用不包含敏感信息的唯一标识。
          minLength: 6
          maxLength: 128
    AsyncResponse:
      type: object
      properties:
        model:
          description: 此次调用使用的名称。
          type: string
        id:
          description: 生成的任务`ID`，调用请求结果接口时使用此`ID`。
          type: string
        request_id:
          description: 用户在客户端请求期间提交的任务编号或平台生成的任务编号。
          type: string
        task_status:
          description: 处理状态，`PROCESSING (处理中)`、`SUCCESS (成功)`、`FAIL (失败)`。结果需要通过查询获取。
          type: string
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
    ChatThinking:
      type: object
      description: 仅 `GLM-4.5` 及以上模型支持此参数配置. 控制大模型是否开启思维链。
      properties:
        type:
          type: string
          description: >-
            是否开启思维链(当开启后 `GLM-5.1` `GLM-5` `GLM-5-Turbo` `GLM-5v-Turbo`
            `GLM-4.7` `GLM-4.5V` 为强制思考，`GLM-4.6` `GLM-4.6V` `GLM-4.5`
            为模型自动判断是否思考), 默认: `enabled`.
          default: enabled
          enum:
            - enabled
            - disabled
        clear_thinking:
          type: boolean
          description: >-
            默认为 `True`。用于控制是否清除历史对话轮次（`previous turns`）中的 `reasoning_content`。详见
            [思考模式](/cn/guide/capabilities/thinking-mode) 
             - `true`（默认）：在本次请求中，系统会忽略/移除历史 `turns` 的 `reasoning_content`，仅使用非推理内容（如用户/助手可见文本、工具调用与结果等）作为上下文输入。适用于普通对话与轻量任务，可降低上下文长度与成本 
             - `false`：保留历史 `turns` 的 `reasoning_content` 并随上下文一同提供给模型。若你希望启用 `Preserved Thinking`，必须在 `messages` 中完整、未修改、按原顺序透传历史 `reasoning_content`；缺失、裁剪、改写或重排会导致效果下降或无法生效。
             - 注意：该参数只影响跨 `turn` 的历史 `thinking blocks`；不改变模型在当前 `turn` 内是否产生/输出 `thinking`
          default: true
          example: true
    FunctionToolSchema:
      type: object
      title: Function Call
      properties:
        type:
          type: string
          default: function
          enum:
            - function
        function:
          $ref: '#/components/schemas/FunctionObject'
      required:
        - type
        - function
      additionalProperties: false
    RetrievalToolSchema:
      type: object
      title: Retrieval
      properties:
        type:
          type: string
          default: retrieval
          enum:
            - retrieval
        retrieval:
          $ref: '#/components/schemas/RetrievalObject'
      required:
        - type
        - retrieval
      additionalProperties: false
    WebSearchToolSchema:
      type: object
      title: Web Search
      properties:
        type:
          type: string
          default: web_search
          enum:
            - web_search
        web_search:
          $ref: '#/components/schemas/WebSearchObject'
      required:
        - type
        - web_search
      additionalProperties: false
    MCPToolSchema:
      type: object
      title: MCP
      properties:
        type:
          type: string
          default: mcp
          enum:
            - mcp
        mcp:
          $ref: '#/components/schemas/MCPObject'
      required:
        - type
        - mcp
      additionalProperties: false
    VisionMultimodalContentItem:
      oneOf:
        - title: 文本
          type: object
          properties:
            type:
              type: string
              enum:
                - text
              description: 内容类型为文本
              default: text
            text:
              type: string
              description: 文本内容
          required:
            - type
            - text
          additionalProperties: false
        - title: 图片
          type: object
          properties:
            type:
              type: string
              enum:
                - image_url
              description: 内容类型为图片`URL`
              default: image_url
            image_url:
              type: object
              description: 图片信息
              properties:
                url:
                  type: string
                  description: >-
                    图片的`URL`地址或`Base64`编码。图像大小上传限制为每张图像`5M`以下，且像素不超过`6000*6000`。支持`jpg、png、jpeg`格式。`GLM-5V-Turbo`
                    `GLM4.6V` `GLM4.5V` 系列限制`50`张。`GLM-4V-Plus-0111`
                    限制`5`张。`GLM-4V-Flash`限制`1`张图像且不支持`Base64`编码。
              required:
                - url
              additionalProperties: false
          required:
            - type
            - image_url
          additionalProperties: false
        - title: 视频
          type: object
          properties:
            type:
              type: string
              enum:
                - video_url
              description: 内容类型为视频输入
              default: video_url
            video_url:
              type: object
              description: 视频信息。注意：`GLM-4V-Plus-0111` 的 `video_url` 参数必须在 `content` 数组的第一位。
              properties:
                url:
                  type: string
                  description: >-
                    视频的`URL`地址。`GLM-5V-Turbo` `GLM-4.6V` `GLM-4.5V` 系列视频大小限制为
                    `200M`
                    以内。`GLM-4V-Plus`视频大小限制为`20M`以内，视频时长不超过`30s`。对于其他多模态模型，视频大小限制为`200M`以内。视频类型：`mp4`，`mkv`，`mov`。
              required:
                - url
              additionalProperties: false
          required:
            - type
            - video_url
          additionalProperties: false
        - title: 文件
          type: object
          properties:
            type:
              type: string
              enum:
                - file_url
              description: >-
                内容类型为文件输入(仅`GLM-5V-Turbo` `GLM-4.6V` `GLM-4.5V`支持，且不支持同时传入
                `file_url` 和 `image_url` 或 `video_url` 参数)
              default: file_url
            file_url:
              type: object
              description: 文件信息。
              properties:
                url:
                  type: string
                  description: >-
                    文件的`URL`地址，不支持`Base64`编码。支持`pdf、txt、word、jsonl、xlsx、pptx`等格式，最多支持`50`个。
              required:
                - url
              additionalProperties: false
          required:
            - type
            - file_url
          additionalProperties: false
    AudioMultimodalContentItem:
      oneOf:
        - title: 文本
          type: object
          properties:
            type:
              type: string
              enum:
                - text
              description: 内容类型为文本
              default: text
            text:
              type: string
              description: 文本内容
          required:
            - type
            - text
          additionalProperties: false
        - title: 音频
          type: object
          properties:
            type:
              type: string
              enum:
                - input_audio
              description: 内容类型为音频输入
              default: input_audio
            input_audio:
              type: object
              description: 音频信息，仅`glm-4-voice`支持音频输入
              properties:
                data:
                  type: string
                  description: 语音文件的`base64`编码。音频最长不超过 `10` 分钟。`1s`音频=`12.5 Tokens`，向上取整。
                format:
                  type: string
                  description: 语音文件的格式，支持`wav`和`mp3`
                  enum:
                    - wav
                    - mp3
              required:
                - data
                - format
              additionalProperties: false
          required:
            - type
            - input_audio
          additionalProperties: false
    FunctionObject:
      type: object
      properties:
        name:
          type: string
          description: 要调用的函数名称。必须是 `a-z、A-Z、0-9`，或包含下划线和破折号，最大长度为 `64`。
          minLength: 1
          maxLength: 64
          pattern: ^[a-zA-Z0-9_-]+$
        description:
          type: string
          description: 函数功能的描述，供模型选择何时以及如何调用函数。
        parameters:
          $ref: '#/components/schemas/FunctionParameters'
      required:
        - name
        - description
        - parameters
    RetrievalObject:
      type: object
      properties:
        knowledge_id:
          type: string
          description: 知识库 `ID`，从平台创建或获取
        prompt_template:
          type: string
          description: >-
            请求模型的提示模板，包含占位符 `{{ knowledge }}` 和 `{{ question }}`
            的自定义请求模板。默认模板：`在文档 `{{ knowledge }}` 中搜索问题 `{{question}}`
            的答案。如果找到答案，仅使用文档中的陈述进行回应；如果没有找到答案，使用你自己的知识回答并告知用户信息不来自文档。不要重复问题，直接开始答案。`
      required:
        - knowledge_id
    WebSearchObject:
      type: object
      properties:
        enable:
          type: boolean
          description: 是否启用搜索功能，默认值为 `false`，启用时设置为 `true`
        search_engine:
          type: string
          description: >-
            搜索引擎类型，默认为
            `search_std`；支持`search_std、search_pro、search_pro_sogou、search_pro_quark`。
          enum:
            - search_std
            - search_pro
            - search_pro_sogou
            - search_pro_quark
        search_query:
          type: string
          description: 强制触发搜索
        search_intent:
          type: string
          description: >-
            是否进行搜索意图识别，默认执行搜索意图识别。`true`：执行搜索意图识别，有搜索意图后执行搜索；`false`：跳过搜索意图识别，直接执行搜索
        count:
          type: integer
          description: >-
            返回结果的条数。可填范围：`1-50`，最大单次搜索返回`50`条，默认为`10`。支持的搜索引擎：`search_std、search_pro、search_pro_sogou`。对于`search_pro_sogou`:
            可选枚举值，`10、20、30、40、50`
          minimum: 1
          maximum: 50
        search_domain_filter:
          type: string
          description: |-
            用于限定搜索结果的范围，仅返回指定白名单域名的内容。
            白名单域名:（如 `www.example.com`）。
            支持的搜索引擎：`search_std、search_pro、search_pro_sogou`
        search_recency_filter:
          type: string
          description: >-
            搜索指定时间范围内的网页。默认为`noLimit`。可填值：`oneDay`（一天内）、`oneWeek`（一周内）、`oneMonth`（一个月内）、`oneYear`（一年内）、`noLimit`（不限，默认）。支持的搜索引擎：`search_std、search_pro、search_pro_sogou、search_pro_quark`
          enum:
            - oneDay
            - oneWeek
            - oneMonth
            - oneYear
            - noLimit
        content_size:
          type: string
          description: >-
            控制网页摘要的字数。默认值为`medium`。`medium`：返回摘要信息，满足大模型的基础推理需求。`high`：最大化上下文，信息量较大但内容详细，适合需要信息细节的场景。
          enum:
            - medium
            - high
        result_sequence:
          type: string
          description: 指定搜索结果返回的顺序是在模型回复结果之前还是之后，可选值：`before`、`after`，默认 `after`
          enum:
            - before
            - after
        search_result:
          type: boolean
          description: 是否返回搜索来源的详细信息，默认值 `false`
        require_search:
          type: boolean
          description: 是否强制搜索结果才返回回答，默认值 `false`
        search_prompt:
          type: string
          description: |-
            用于定制搜索结果处理的`Prompt`，默认`Prompt`：

            你是一位智能问答专家，具备整合信息的能力，能够进行时间识别、语义理解与矛盾信息清洗处理。
            当前日期是{{current_date}}，请以此时间为唯一基准，参考以下信息，全面、准确地回答用户问题。
            仅提炼有价值的内容用于回答，确保答案具有实时性与权威性，直接陈述答案，无需说明数据来源或内部处理过程。
      required:
        - search_engine
    MCPObject:
      type: object
      properties:
        server_label:
          description: >-
            `mcp server`标识，如果连接智谱的`mcp server`，以`mcp
            code`填充该字段，且无需填写`server_url`
          type: string
        server_url:
          description: '`mcp server`地址'
          type: string
        transport_type:
          description: 传输类型
          type: string
          default: streamable-http
          enum:
            - sse
            - streamable-http
        allowed_tools:
          description: 允许的工具集合
          type: array
          items:
            type: string
        headers:
          description: '`mcp server` 需要的鉴权信息'
          type: object
      required:
        - server_label
    FunctionParameters:
      type: object
      description: 使用 `JSON Schema` 定义的参数。必须传递 `JSON Schema` 对象以准确定义接受的参数。如果调用函数时不需要参数，则省略。
      additionalProperties: true
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````

> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 图像生成

> 使用 [GLM-Image](/cn/guide/models/image-generation/glm-image) 等系列模型从文本提示生成高质量图像。通过对用户文字描述快速、精准的理解，让 `AI` 的图像表达更加精确和个性化。



## OpenAPI

````yaml /openapi/openapi.json post /paas/v4/images/generations
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/images/generations:
    post:
      tags:
        - 模型 API
      summary: 图像生成
      description: >-
        使用 [GLM-Image](/cn/guide/models/image-generation/glm-image)
        等系列模型从文本提示生成高质量图像。通过对用户文字描述快速、精准的理解，让 `AI` 的图像表达更加精确和个性化。
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateImageRequest'
            examples:
              图像生成示例:
                value:
                  model: glm-image
                  prompt: 一只可爱的小猫咪，坐在阳光明媚的窗台上，背景是蓝天白云.
                  size: 1280x1280
        required: true
      responses:
        '200':
          description: 业务处理成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ImageGenerationResponse'
        default:
          description: 请求失败。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    CreateImageRequest:
      type: object
      required:
        - model
        - prompt
      properties:
        model:
          type: string
          description: 模型编码
          enum:
            - glm-image
            - cogview-4-250304
            - cogview-4
            - cogview-3-flash
          example: glm-image
        prompt:
          type: string
          description: 所需图像的文本描述
          example: 一只可爱的小猫咪
        quality:
          type: string
          description: >-
            生成图像的质量，`glm-image` 默认为 `hd`, 其它默认为 `standard`。`hd`:
            生成更精细、细节更丰富的图像，整体一致性更高，耗时约`20`秒；`standard`:
            快速生成图像，适合对生成速度有较高要求的场景，耗时约`5-10`秒。`glm-image` 仅支持 `hd`。
          enum:
            - hd
            - standard
          default: hd
        size:
          type: string
          description: >-
            图片尺寸，`glm-image` 推荐枚举值：`1280x1280` (默认), `1568×1056`, `1056×1568`,
            `1472×1088`, `1088×1472`, `1728×960`,
            `960×1728`。自定义参数:长宽推荐设置在`1024px-2048px`范围内,并保证最大像素数不超过`2^22px`;长宽均需为`32`的整数倍。
             其它模型推荐枚举值：`1024x1024` (默认), `768x1344`, `864x1152`, `1344x768`, `1152x864`, `1440x720`, `720x1440`。自定义参数：长宽均需满足`512px-2048px`之间，需被`16`整除，并保证最大像素数不超过`2^21px`。
          default: 1280x1280
          example: 1280x1280
        watermark_enabled:
          type: boolean
          description: |-
            控制`AI`生成图片时是否添加水印。
             - `true`: 默认启用`AI`生成的显式水印及隐式数字水印，符合政策要求。
             - `false`: 关闭所有水印，仅允许已签署免责声明的客户使用，签署路径：个人中心-安全管理-去水印管理
          example: true
        user_id:
          type: string
          description: >-
            终端用户的唯一`ID`，协助平台对终端用户的违规行为、生成违法及不良信息或其他滥用行为进行干预。`ID`长度要求：最少`6`个字符，最多`128`个字符。
          minLength: 6
          maxLength: 128
    ImageGenerationResponse:
      type: object
      properties:
        created:
          type: integer
          description: 请求创建时间，是以秒为单位的`Unix`时间戳
        data:
          type: array
          description: 数组，包含生成的图片`URL`。目前数组中只包含一张图片。
          items:
            type: object
            properties:
              url:
                type: string
                description: 图片链接。图片的临时链接有效期为`30`天，请及时转存图片。
            required:
              - url
        content_filter:
          type: array
          description: 返回内容安全的相关信息
          items:
            type: object
            properties:
              role:
                type: string
                description: >-
                  安全生效环节，包括 `role = assistant` 模型推理，`role = user` 用户输入，`role =
                  history` 历史上下文
                enum:
                  - assistant
                  - user
                  - history
              level:
                type: integer
                description: 严重程度 `level 0-3`，`level 0`表示最严重，`3`表示轻微
                minimum: 0
                maximum: 3
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````


> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 网络搜索

> `Web Search API` 是一个专给大模型用的搜索引擎，在传统搜索引擎网页读取、排序的能力基础上，增强了意图识别能力，返回更适合大模型处理的结果（网页标题、`URL`、摘要、名称、图标等）。支持意图增强检索、结构化输出和多引擎支持。见 [网络搜索服务](/cn/guide/tools/web-search)



## OpenAPI

````yaml /openapi/openapi.json post /paas/v4/web_search
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/web_search:
    post:
      tags:
        - 工具 API
      summary: 网络搜索
      description: >-
        `Web Search API`
        是一个专给大模型用的搜索引擎，在传统搜索引擎网页读取、排序的能力基础上，增强了意图识别能力，返回更适合大模型处理的结果（网页标题、`URL`、摘要、名称、图标等）。支持意图增强检索、结构化输出和多引擎支持。见
        [网络搜索服务](/cn/guide/tools/web-search)
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/WebSearchRequest'
        required: true
      responses:
        '200':
          description: 业务处理成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/WebSearchResponse'
        default:
          description: >-
            请求失败。可能的错误码：1701-网络搜索并发已达上限，请稍后重试或减少并发请求；1702-系统未找到可用的搜索引擎服务，请检查配置或联系管理员；1703-搜索引擎未返回有效数据，请调整查询条件。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    WebSearchRequest:
      type: object
      properties:
        search_query:
          type: string
          description: 需要进行搜索的内容，建议搜索 `query` 不超过 `70` 个字符。
          maxLength: 70
        search_engine:
          type: string
          description: |-
            要调用的搜索引擎编码。目前支持：
            `search_std`：智谱基础版搜索引擎
            `search_pro`：智谱高阶版搜索引擎
            `search_pro_sogou`：搜狗
            `search_pro_quark`：夸克搜索
          example: search_std
          enum:
            - search_std
            - search_pro
            - search_pro_sogou
            - search_pro_quark
        search_intent:
          type: boolean
          description: |-
            是否进行搜索意图识别，默认不执行搜索意图识别。
            `true`：执行搜索意图识别，有搜索意图后执行搜索
            `false`：跳过搜索意图识别，直接执行搜索
          default: false
        count:
          type: integer
          description: |-
            返回结果的条数。可填范围：`1-50`，最大单次搜索返回`50`条，默认为`10`。
            支持的搜索引擎：`search_pro_sogou`、`search_std`、`search_pro`
            `search_pro_sogou`: 可选枚举值，10、20、30、40、50
          minimum: 1
          maximum: 50
          default: 10
        search_domain_filter:
          type: string
          description: |-
            用于限定搜索结果的范围，仅返回指定白名单域名的内容。
            白名单域名:（如 `www.example.com`）
            支持的搜索引擎：`search_std、search_pro 、search_pro_sogou`
        search_recency_filter:
          type: string
          description: >-
            搜索指定时间范围内的网页。默认为
            `noLimit`。可填值：`oneDay`（一天内）、`oneWeek`（一周内）、`oneMonth`（一个月内）、`oneYear`（一年内）、`noLimit`（不限，默认）。支持的搜索引擎：`search_std、search_pro、search_pro_Sogou、search_pro_quark`
          default: noLimit
          enum:
            - oneDay
            - oneWeek
            - oneMonth
            - oneYear
            - noLimit
        content_size:
          type: string
          description: >-
            控制返回网页内容的长短。`medium`：返回摘要信息，满足大模型的基础推理需求，满足常规问答任务的信息检索需求。`high`：最大化上下文，信息量较大但内容详细，适合需要信息细节的场景。支持的搜索引擎：`search_std、search_pro、search_pro_Sogou、search_pro_quark`
          enum:
            - medium
            - high
        request_id:
          type: string
          description: 由用户端传递，需要唯一；用于区分每次请求的唯一标识符。如果用户端未提供，平台将默认生成。
        user_id:
          type: string
          description: >-
            终端用户的唯一`ID`，帮助平台对终端用户的非法活动、生成非法不当信息或其他滥用行为进行干预。`ID`长度要求：至少`6`个字符，最多`128`个字符。
          minLength: 6
          maxLength: 128
      required:
        - search_query
        - search_engine
        - search_intent
    WebSearchResponse:
      type: object
      properties:
        id:
          type: string
          description: 任务 ID
        created:
          type: integer
          description: 请求创建时间，是以秒为单位的 `Unix` 时间戳
        request_id:
          type: string
          description: 请求标识符
        search_intent:
          type: array
          description: 搜索意图结果
          items:
            type: object
            properties:
              query:
                type: string
                description: 原始搜索query
              intent:
                type: string
                description: >-
                  识别的意图类型。`SEARCH_ALL` = 搜索全网，`SEARCH_NONE` =
                  无搜索意图，`SEARCH_ALWAYS` = 强制搜索模式：当`search_intent=false`时返回此值
                enum:
                  - SEARCH_ALL
                  - SEARCH_NONE
                  - SEARCH_ALWAYS
              keywords:
                type: string
                description: 改写后的搜索关键词
        search_result:
          type: array
          description: 搜索结果
          items:
            type: object
            properties:
              title:
                type: string
                description: 标题
              content:
                type: string
                description: 内容摘要
              link:
                type: string
                description: 结果链接
              media:
                type: string
                description: 网站名称
              icon:
                type: string
                description: 网站图标
              refer:
                type: string
                description: 角标序号
              publish_date:
                type: string
                description: 网站发布时间
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````


> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 网页阅读

> 读取并解析指定 `URL` 的网页内容，可选择返回格式、支持控制缓存、图片保留与摘要选项等。



## OpenAPI

````yaml /openapi/openapi.json post /paas/v4/reader
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/reader:
    post:
      tags:
        - 工具 API
      summary: 网页阅读
      description: 读取并解析指定 `URL` 的网页内容，可选择返回格式、支持控制缓存、图片保留与摘要选项等。
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ReaderRequest'
            examples:
              Basic:
                value:
                  url: https://www.example.com
        required: true
      responses:
        '200':
          description: 业务处理成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ReaderResponse'
        default:
          description: 请求失败。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    ReaderRequest:
      type: object
      properties:
        url:
          type: string
          description: 需要抓取的`url`
        timeout:
          type: integer
          description: 请求超时时间（秒），默认值 `20`
          default: 20
        no_cache:
          type: boolean
          description: 是否禁用缓存（`true`/`false`），默认值 `false`
          default: false
        return_format:
          type: string
          description: 返回格式（如：`markdown`、`text`等），默认值 `markdown`
          default: markdown
        retain_images:
          type: boolean
          description: 是否保留图片（`true`/`false`），默认值 `true`
          default: true
        no_gfm:
          type: boolean
          description: 是否禁用 `GitHub Flavored Markdown`（`true`/`false`），默认值 `false`
          default: false
        keep_img_data_url:
          type: boolean
          description: 是否保留图片数据 `URL`（`true`/`false`），默认值 `false`
          default: false
        with_images_summary:
          type: boolean
          description: 是否包含图片摘要（`true`/`false`），默认值 `false`
          default: false
        with_links_summary:
          type: boolean
          description: 是否包含链接摘要（`true`/`false`），默认值 `false`
          default: false
      required:
        - url
    ReaderResponse:
      type: object
      properties:
        id:
          description: 任务 `ID`
          type: string
        created:
          type: integer
          format: int64
          description: 请求创建时间，是以秒为单位的 `Unix` 时间戳
        request_id:
          type: string
          description: 由用户端传递，需要唯一；用于区分每次请求的唯一标识符。如果用户端未提供，平台将默认生成。
        model:
          type: string
          description: 模型编码
        reader_result:
          type: object
          description: 网页阅读结果
          properties:
            content:
              type: string
              description: 网页解析后的主要内容（正文、图片、链接等标记）
            description:
              type: string
              description: 网页简要描述
            title:
              type: string
              description: 网页标题
            url:
              type: string
              description: 网页原始地址
            external:
              type: object
              description: 网页引用的外部资源对象
              properties:
                stylesheet:
                  type: object
                  description: 外部样式表集合
                  additionalProperties:
                    type: object
                    properties:
                      type:
                        type: string
                        description: 样式表类型，通常为`text/css`
            metadata:
              type: object
              description: 页面元数据信息
              properties:
                keywords:
                  type: string
                  description: 页面关键词
                viewport:
                  type: string
                  description: 页面视口设置
                description:
                  type: string
                  description: 元数据描述
                format-detection:
                  type: string
                  description: 格式检测设置，如`telephone=no`
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````

> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# OCR 服务

> 上传图片文件，使用指定工具类型进行 OCR（光学字符识别），支持手写体、文字等识别模式，见 [OCR 服务](/cn/guide/tools/zhipu-ocr)



## OpenAPI

````yaml /openapi/openapi.json post /paas/v4/files/ocr
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/files/ocr:
    post:
      tags:
        - 工具 API
      summary: OCR 服务
      description: >-
        上传图片文件，使用指定工具类型进行 OCR（光学字符识别），支持手写体、文字等识别模式，见 [OCR
        服务](/cn/guide/tools/zhipu-ocr)
      requestBody:
        content:
          multipart/form-data:
            schema:
              type: object
              properties:
                file:
                  type: string
                  format: binary
                  description: 待识别的图片文件（如 JPG、PNG）
                tool_type:
                  type: string
                  enum:
                    - hand_write
                  description: OCR识别工具类型，可选 hand_write（手写体识别）
                language_type:
                  type: string
                  enum:
                    - CHN_ENG
                    - AUTO
                    - ENG
                    - JAP
                    - KOR
                    - FRE
                    - SPA
                    - POR
                    - GER
                    - ITA
                    - RUS
                    - DAN
                    - DUT
                    - MAL
                    - SWE
                    - IND
                    - POL
                    - ROM
                    - TUR
                    - GRE
                    - HUN
                    - THA
                    - VIE
                    - ARA
                    - HIN
                  description: 语言/识别模型类型，可选 CHN_ENG等
                probability:
                  type: boolean
                  example: true
                  default: false
                  description: 是否返回置信度（概率）信息。true 为返回
              required:
                - file
                - tool_type
        required: true
      responses:
        '200':
          description: 结果获取成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/OCRResultResponse'
        default:
          description: 请求失败。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    OCRResultResponse:
      type: object
      properties:
        task_id:
          type: string
          description: OCR识别任务ID
          example: ce2641ced3e34e67b47f3b0feeb25aee
        message:
          type: string
          description: 结果状态描述
          example: 成功
        status:
          type: string
          enum:
            - succeeded
            - failed
          description: 任务处理状态
          example: succeeded
        words_result_num:
          type: integer
          description: 识别到的文本块/行数
          example: 4
        words_result:
          type: array
          description: 每个识别文本块/行的详细结果
          items:
            type: object
            properties:
              location:
                type: object
                properties:
                  left:
                    type: integer
                    example: 79
                  top:
                    type: integer
                    example: 122
                  width:
                    type: integer
                    example: 1483
                  height:
                    type: integer
                    example: 182
                required:
                  - left
                  - top
                  - width
                  - height
              words:
                type: string
                description: 识别出的文本内容
                example: 你好,世界!
              probability:
                type: object
                description: 置信度信息
                properties:
                  average:
                    type: number
                    example: 0.7320847511
                  variance:
                    type: number
                    example: 0.08768635988
                  min:
                    type: number
                    example: 0.3193874359
                required:
                  - average
                  - variance
                  - min
            required:
              - location
              - words
              - probability
      required:
        - task_id
        - message
        - status
        - words_result_num
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````

> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 文件解析

> 创建文件解析任务，支持多种文件格式和解析工具。见 [文件解析服务](/cn/guide/tools/file-parser)



## OpenAPI

````yaml /openapi/openapi.json post /paas/v4/files/parser/create
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/files/parser/create:
    post:
      tags:
        - 工具 API
      summary: 文件解析
      description: 创建文件解析任务，支持多种文件格式和解析工具。见 [文件解析服务](/cn/guide/tools/file-parser)
      requestBody:
        content:
          multipart/form-data:
            schema:
              type: object
              properties:
                file:
                  type: string
                  format: binary
                  description: 待解析文件
                tool_type:
                  type: string
                  enum:
                    - lite
                    - expert
                    - prime
                  description: 使用的解析工具类型
                file_type:
                  type: string
                  enum:
                    - PDF
                    - DOCX
                    - DOC
                    - XLS
                    - XLSX
                    - PPT
                    - PPTX
                    - PNG
                    - JPG
                    - JPEG
                    - CSV
                    - TXT
                    - MD
                    - HTML
                    - BMP
                    - GIF
                    - WEBP
                    - HEIC
                    - EPS
                    - ICNS
                    - IM
                    - PCX
                    - PPM
                    - TIFF
                    - XBM
                    - HEIF
                    - JP2
                  description: >-
                    文件类型。Lite支持：pdf,docx,doc,xls,xlsx,ppt,pptx,png,jpg,jpeg,csv,txt,md。Expert支持：pdf。Prime支持：pdf,docx,doc,xls,xlsx,ppt,pptx,png,jpg,jpeg,csv,txt,md,html,bmp,gif,webp,heic,eps,icns,im,pcx,ppm,tiff,xbm,heif,jp2
              required:
                - file
                - tool_type
        required: true
      responses:
        '200':
          description: 任务创建成功
          content:
            application/json:
              schema:
                type: object
                properties:
                  success:
                    type: boolean
                    example: true
                  message:
                    type: string
                    example: 任务创建成功
                  task_id:
                    type: string
                    example: task_123456789
        default:
          description: 请求失败。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````

> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 文件解析(同步)

> 创建文件解析任务，支持多种文件格式和解析工具。见 [文件解析服务](/cn/guide/tools/file-parser)



## OpenAPI

````yaml /openapi/openapi.json post /paas/v4/files/parser/sync
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/files/parser/sync:
    post:
      tags:
        - 工具 API
      summary: 文件解析(同步)
      description: 创建文件解析任务，支持多种文件格式和解析工具。见 [文件解析服务](/cn/guide/tools/file-parser)
      requestBody:
        content:
          multipart/form-data:
            schema:
              type: object
              properties:
                file:
                  type: string
                  format: binary
                  description: 待解析文件
                tool_type:
                  type: string
                  enum:
                    - prime-sync
                  description: 使用的解析工具类型
                file_type:
                  type: string
                  enum:
                    - WPS
                    - PDF
                    - DOCX
                    - DOC
                    - XLS
                    - XLSX
                    - PPT
                    - PPTX
                    - PNG
                    - JPG
                    - JPEG
                    - CSV
                    - TXT
                    - MD
                    - HTML
                    - BMP
                    - GIF
                    - WEBP
                    - HEIC
                    - EPS
                    - ICNS
                    - IM
                    - PCX
                    - PPM
                    - TIFF
                    - XBM
                    - HEIF
                    - JP2
                  description: >-
                    文件类型支持：pdf,docx,doc,xls,xlsx,ppt,pptx,png,jpg,jpeg,csv,txt,md,html,bmp,gif,webp,heic,eps,icns,im,pcx,ppm,tiff,xbm,heif,jp2
              required:
                - file
                - tool_type
        required: true
      responses:
        '200':
          description: 结果获取成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FileParseResultResponse'
        default:
          description: 请求失败。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    FileParseResultResponse:
      type: object
      properties:
        status:
          type: string
          enum:
            - processing
            - succeeded
            - failed
          description: 任务处理状态
          example: succeeded
        message:
          type: string
          description: 结果状态描述
          example: 结果获取成功
        content:
          type: string
          description: 当`format_type=text`时返回的解析文本内容
          example: 这是解析后的文本内容...
          nullable: true
        task_id:
          type: string
          description: 文件解析任务`ID`
          example: task_123456789
        parsing_result_url:
          type: string
          description: 当`format_type=download_link`时返回的结果下载链接
          example: https://example.com/download/result.zip
          nullable: true
      required:
        - status
        - message
        - task_id
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````

> ## Documentation Index
> Fetch the complete documentation index at: https://docs.bigmodel.cn/llms.txt
> Use this file to discover all available pages before exploring further.

# 解析结果

> 异步获取文件解析任务的结果，支持返回纯文本或下载链接格式。见 [文件解析服务](/cn/guide/tools/file-parser)



## OpenAPI

````yaml /openapi/openapi.json get /paas/v4/files/parser/result/{taskId}/{format_type}
openapi: 3.0.1
info:
  title: ZHIPU AI API
  description: ZHIPU AI 接口提供强大的 AI 能力，包括聊天对话、工具调用和视频生成。
  license:
    name: ZHIPU AI 开发者协议和政策
    url: https://chat.z.ai/legal-agreement/terms-of-service
  version: 1.0.0
  contact:
    name: Z.AI 开发者
    url: https://chat.z.ai/legal-agreement/privacy-policy
    email: user_feedback@z.ai
servers:
  - url: https://open.bigmodel.cn/api/
    description: 开放平台服务
security:
  - bearerAuth: []
tags:
  - name: 模型 API
    description: Chat API
  - name: 工具 API
    description: Web Search API
  - name: Agent API
    description: Agent API
  - name: 文件 API
    description: File API
  - name: 知识库 API
    description: Knowledge API
  - name: 实时 API
    description: Realtime API
  - name: 批处理 API
    description: Batch API
  - name: 助理 API
    description: Assistant API
  - name: 智能体 API（旧）
    description: QingLiu Agent API
paths:
  /paas/v4/files/parser/result/{taskId}/{format_type}:
    get:
      tags:
        - 工具 API
      summary: 解析结果
      description: 异步获取文件解析任务的结果，支持返回纯文本或下载链接格式。见 [文件解析服务](/cn/guide/tools/file-parser)
      parameters:
        - name: taskId
          in: path
          description: 文件解析任务ID
          required: true
          schema:
            type: string
            example: task_123456789
        - name: format_type
          in: path
          description: 结果返回格式类型
          required: true
          schema:
            type: string
            enum:
              - text
              - download_link
            example: text
      responses:
        '200':
          description: 结果获取成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FileParseResultResponse'
        default:
          description: 请求失败。
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
components:
  schemas:
    FileParseResultResponse:
      type: object
      properties:
        status:
          type: string
          enum:
            - processing
            - succeeded
            - failed
          description: 任务处理状态
          example: succeeded
        message:
          type: string
          description: 结果状态描述
          example: 结果获取成功
        content:
          type: string
          description: 当`format_type=text`时返回的解析文本内容
          example: 这是解析后的文本内容...
          nullable: true
        task_id:
          type: string
          description: 文件解析任务`ID`
          example: task_123456789
        parsing_result_url:
          type: string
          description: 当`format_type=download_link`时返回的结果下载链接
          example: https://example.com/download/result.zip
          nullable: true
      required:
        - status
        - message
        - task_id
    Error:
      type: object
      properties:
        error:
          required:
            - code
            - message
          type: object
          properties:
            code:
              type: string
            message:
              type: string
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      description: >-
        使用以下格式进行身份验证：Bearer [<your api
        key>](https://bigmodel.cn/usercenter/proj-mgmt/apikeys)

````
---

## 5. MiniMax
> ## Documentation Index
> Fetch the complete documentation index at: https://platform.minimaxi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

<AgentInstructions>

## Submitting Feedback

If you encounter incorrect, outdated, or confusing documentation on this page, submit feedback:

POST https://platform.minimaxi.com/docs/feedback

```json
{
  "path": "/guides/quickstart-preparation",
  "feedback": "Description of the issue"
}
```

Only submit feedback when you have something specific and actionable to report.

</AgentInstructions>

# 前置准备

> 在开始使用 MiniMax API 之前，需要完成账户注册和 API Key 获取。

<Steps>
  <Step title="账户注册/登录">
    API 调用前，需在 MiniMax 开放平台进行[账户注册](https://platform.minimaxi.com/login)，企业团队注册请参考页面底部说明.
  </Step>

  <Step title="获取 API Key">
    * **按量付费**：通过 [接口密钥 > 创建新的 API Key](https://platform.minimaxi.com/user-center/basic-information/interface-key)，获取 **API Key**
      <Note>按量付费支持使用所有模态模型，包括文本、视频、语音、图像等</Note>
    * **Token Plan**：通过 [接口密钥 > 创建 Token Plan Key](https://platform.minimaxi.com/user-center/payment/token-plan)，获取 **API Key**
      <Note>Token Plan 支持使用 MiniMax 全模态模型，详情见 [Token Plan 概要](https://platform.minimaxi.com/docs/token-plan/intro)</Note>

    生成 API Key 后，建议将其存储为环境变量或保存到 `.env` 文件中

    ```bash theme={null}
    # 推荐使用 Anthropic API 兼容
    export ANTHROPIC_BASE_URL=https://api.minimaxi.com/anthropic
    export ANTHROPIC_API_KEY=${YOUR_API_KEY}
    ```
  </Step>

  <Step title="账户充值">
    通过 [账户管理 > 余额](https://platform.minimaxi.com/user-center/payment/balance)，按需充值
  </Step>
</Steps>

***

<Accordion title="企业团队注册说明">
  建议采用**主账号+子账号**的形式创建和管理。

1. 在 [MiniMax 开放平台](https://platform.minimaxi.com/user-center/basic-information) 注册一个账号（此账号即为主账号，注册时填写的姓名与手机号会成为本企业账号的管理员信息）
2. 登录该主账号，在 [账户管理 > 子账号](https://platform.minimaxi.com/user-center/basic-information/child-account)，创建您所需要数量的子账户（子账号的创建数量暂时没有限制）
3. 为您企业的人员，分配不同的子账户，进行登录使用

**子账户权限说明：**

* 子账号和主账号享用相同的使用权益与速率限制，子账号和主账号的 API 消耗共享，统一结算
* 子账号无查看和管理"支付"权限
  </Accordion>

> ## Documentation Index
> Fetch the complete documentation index at: https://platform.minimaxi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

<AgentInstructions>

## Submitting Feedback

If you encounter incorrect, outdated, or confusing documentation on this page, submit feedback:

POST https://platform.minimaxi.com/docs/feedback

```json
{
  "path": "/guides/text-generation",
  "feedback": "Description of the issue"
}
```

Only submit feedback when you have something specific and actionable to report.

</AgentInstructions>

# 文本生成

> MiniMax 文本模型，支持多语言编程、Agent 工作流等复杂任务场景。

<Note>
  订阅 [Token Plan](https://platform.minimaxi.com/subscribe/token-plan) ，即可以超低价格使用 MiniMax 全模态模型!
</Note>

## 模型概览

MiniMax 提供多款文本模型，满足不同场景需求。**MiniMax-M2.7** 在编程、工具调用和搜索、办公等生产力场景都达到或刷新了行业的 SOTA。

### 支持模型

| 模型名称                   |  上下文窗口  | 模型介绍                                    |
| :--------------------- | :-----: | :-------------------------------------- |
| MiniMax-M2.7           | 204,800 | **开启模型的自我迭代**（输出速度约 60 TPS）             |
| MiniMax-M2.7-highspeed | 204,800 | **M2.7 极速版：效果不变，更快，更敏捷**（输出速度约 100 TPS） |
| MiniMax-M2.5           | 204,800 | **顶尖性能与极致性价比，轻松驾驭复杂任务**（输出速度约 60 TPS）   |
| MiniMax-M2.5-highspeed | 204,800 | **M2.5 极速版：效果不变，更快，更敏捷**（输出速度约 100 TPS） |
| MiniMax-M2.1           | 204,800 | **强大多语言编程能力，全面升级编程体验**（输出速度约 60 TPS）    |
| MiniMax-M2.1-highspeed | 204,800 | **M2.1 极速版：效果不变，更快，更敏捷**（输出速度约 100 TPS） |
| MiniMax-M2             | 204,800 | **专为高效编码与 Agent 工作流而生**                 |

<Note>
  TPS（Tokens Per Second）的计算方式详见[常见问题 > 接口相关](/faq/about-apis#%E9%97%AE%E6%96%87%E6%9C%AC%E6%A8%A1%E5%9E%8B%E7%9A%84-tpstokens-per-second%E6%98%AF%E5%A6%82%E4%BD%95%E8%AE%A1%E7%AE%97%E7%9A%84)。
</Note>

### **MiniMax M2.7** 核心亮点

<AccordionGroup>
  <Accordion title="端到端工程与多基准领先">
    M2.7 在真实的软件工程中有优异的表现，包括端到端的完整项目交付，分析日志排查 Bug、代码安全，机器学习等。在基准测试 SWE-Pro 中，M2.7 得分56.22%，几乎接近Opus最好的水平。这一能力同样延伸到了端到端的完整项目交付场景（VIBE-Pro 55.6%）以及对复杂工程系统的深层理解Terminal Bench 2（57.0%）。
  </Accordion>

  <Accordion title="专业办公、复杂 Skills 与 OpenClaw	">
    在专业办公领域，我们提升了模型在各领域的专业知识和任务交付能力，在 GDPval-AA 的ELO得分是1495，为开源最高。M2.7 对 Office 三件套 Excel/PPT/Word 的复杂编辑能力显著提升，能更好地完成多轮修改和高保真的编辑。M2.7具备与复杂环境交互的能力，M2.7 在 40 个复杂 skills (> 2000 Token) 的 case 上，仍能保持 97% 的 skills 遵循率。在OpenClaw的使用中，M2.7相比于M2.5也有了显著的提升，在MMClaw的评测中接近最新的Sonnet 4.6。
  </Accordion>

  <Accordion title="身份保持、情商与互动娱乐	">
    M2.7具备优秀的身份保持能力和情商，除了生产力使用外，给互动娱乐场景的创新也准备了空间。
  </Accordion>
</AccordionGroup>

<Note>
  更多模型介绍请参考 [MiniMax M2.7](https://www.minimaxi.com/news/minimax-m27-zh)
</Note>

***

## URL 配置

调用 MiniMax 模型前，请先准备好以下信息：

| 字段                          | 值                                                                                     |
| :-------------------------- | :------------------------------------------------------------------------------------ |
| `base_url`（Anthropic 兼容，推荐） | `https://api.minimaxi.com/anthropic`                                                  |
| `base_url`（OpenAI 兼容）       | `https://api.minimaxi.com/v1`                                                         |
| `api_key`                   | [获取 Token Plan API Key](https://platform.minimaxi.com/user-center/payment/token-plan) |
| `model`                     | 见上方[支持模型](#支持模型)表                                                                     |

***

## 调用示例

MiniMax 同时兼容 Anthropic 和 OpenAI 两种 API 协议格式，下面给出两套等价的非流式样例。需要流式响应时，把请求里的 `stream` 改成 `true` 即可。

### Anthropic 兼容（推荐）

支持 thinking 块、interleaved thinking 等高级特性，是默认推荐路径。

<CodeGroup>
  ```bash curl theme={null}
  curl https://api.minimaxi.com/anthropic/v1/messages \
    -H "Authorization: Bearer <MINIMAX_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{
      "model": "MiniMax-M2.7",
      "max_tokens": 1000,
      "messages": [
        {"role": "user", "content": "Hi, how are you?"}
      ]
    }'
  ```

  ```python Python theme={null}
  # 首次使用前请先安装 Anthropic SDK：`pip install anthropic`
  import anthropic

  client = anthropic.Anthropic(
      base_url="https://api.minimaxi.com/anthropic",
      api_key="<MINIMAX_API_KEY>",
  )

  message = client.messages.create(
      model="MiniMax-M2.7",
      max_tokens=1000,
      messages=[
          {"role": "user", "content": "Hi, how are you?"}
      ],
  )

  for block in message.content:
      if block.type == "thinking":
          print(f"Thinking:\n{block.thinking}\n")
      elif block.type == "text":
          print(f"Text:\n{block.text}\n")
  ```

  ```javascript Node.js theme={null}
  // 首次使用前请先安装 Anthropic SDK：`npm install @anthropic-ai/sdk`
  import Anthropic from "@anthropic-ai/sdk";

  const client = new Anthropic({
    baseURL: "https://api.minimaxi.com/anthropic",
    apiKey: "<MINIMAX_API_KEY>",
  });

  const message = await client.messages.create({
    model: "MiniMax-M2.7",
    max_tokens: 1000,
    messages: [
      { role: "user", content: "Hi, how are you?" },
    ],
  });

  for (const block of message.content) {
    if (block.type === "thinking") {
      console.log(`Thinking:\n${block.thinking}\n`);
    } else if (block.type === "text") {
      console.log(`Text:\n${block.text}\n`);
    }
  }
  ```
</CodeGroup>

### OpenAI 兼容

如果你的项目已经接入 OpenAI SDK，把 `base_url` 和 `model` 换成下方的值即可直接复用，无需迁移到新 SDK。

<CodeGroup>
  ```bash curl theme={null}
  curl https://api.minimaxi.com/v1/chat/completions \
    -H "Authorization: Bearer <MINIMAX_API_KEY>" \
    -H "Content-Type: application/json" \
    -d '{
      "model": "MiniMax-M2.7",
      "messages": [
        {"role": "user", "content": "Hi, how are you?"}
      ]
    }'
  ```

  ```python Python theme={null}
  # 首次使用前请先安装 OpenAI SDK：`pip install openai`
  from openai import OpenAI

  client = OpenAI(
      base_url="https://api.minimaxi.com/v1",
      api_key="<MINIMAX_API_KEY>",
  )

  response = client.chat.completions.create(
      model="MiniMax-M2.7",
      messages=[
          {"role": "user", "content": "Hi, how are you?"},
      ],
  )

  print(response.choices[0].message.content)
  ```

  ```javascript Node.js theme={null}
  // 首次使用前请先安装 OpenAI SDK：`npm install openai`
  import OpenAI from "openai";

  const client = new OpenAI({
    baseURL: "https://api.minimaxi.com/v1",
    apiKey: "<MINIMAX_API_KEY>",
  });

  const response = await client.chat.completions.create({
    model: "MiniMax-M2.7",
    messages: [
      { role: "user", content: "Hi, how are you?" },
    ],
  });

  console.log(response.choices[0].message.content);
  ```
</CodeGroup>

***

## API 参考

<Columns cols={2}>
  <Card title="Anthropic API 兼容（推荐）" icon="book-open" href="/api-reference/text-anthropic-api" cta="查看文档">
    通过 Anthropic SDK 调用 MiniMax 模型，支持流式输出和 Interleaved Thinking
  </Card>

  <Card title="OpenAI API 兼容" icon="book-open" href="/api-reference/text-openai-api" cta="查看文档">
    通过 OpenAI SDK 调用 MiniMax 模型
  </Card>

  <Card title="文本生成" icon="file-text" href="/api-reference/text-post" cta="查看文档">
    直接通过 HTTP 请求调用文本生成接口
  </Card>

  <Card title="在 AI 编程工具里使用 M2.7" icon="code" href="/guides/text-ai-coding-tools" cta="查看文档">
    在 Claude Code、Cursor、Cline 等工具中使用 M2.7
  </Card>
</Columns>

***

## 联系我们

如果在使用 MiniMax 模型过程中遇到任何问题：

* 通过邮箱 [Model@minimaxi.com](mailto:Model@minimaxi.com) 等官方渠道联系我们的技术支持团队
* 在我们的 [Github](https://github.com/MiniMax-AI/MiniMax-M2.7/issues) 仓库提交 Issue

## 相关链接

* [Anthropic SDK 文档](https://docs.anthropic.com/en/api/client-sdks)
* [OpenAI SDK 文档](https://platform.openai.com/docs/libraries)
* [MiniMax M2.7](https://www.minimaxi.com/news/minimax-m27-zh)


> ## Documentation Index
> Fetch the complete documentation index at: https://platform.minimaxi.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

<AgentInstructions>

## Submitting Feedback

If you encounter incorrect, outdated, or confusing documentation on this page, submit feedback:

POST https://platform.minimaxi.com/docs/feedback

```json
{
  "path": "/guides/text-chat",
  "feedback": "Description of the issue"
}
```

Only submit feedback when you have something specific and actionable to report.

</AgentInstructions>

# 文本对话

> M2-her 文本对话模型，专为角色扮演、多轮对话等场景设计。

## 模型概览

**M2-her** 是 MiniMax 专为对话场景优化的文本模型，支持丰富的角色设定和对话历史管理能力。

### 支持模型

| 模型名称   | 上下文窗口 | 模型介绍                     |
| :----- | :---: | :----------------------- |
| M2-her |  64 K | **专为对话场景设计，支持角色扮演和多轮对话** |

### **M2-her** 核心特性

<AccordionGroup>
  <Accordion title="丰富的角色设定能力">
    M2-her 支持多种角色类型配置，包括模型角色（system）、用户角色（user\_system）、对话分组（group）等，让您可以灵活构建复杂的对话场景。
  </Accordion>

  <Accordion title="示例对话学习">
    通过 sample\_message\_user 和 sample\_message\_ai，您可以为模型提供示例对话，帮助模型更好地理解期望的对话风格和回复模式。
  </Accordion>

  <Accordion title="上下文记忆">
    模型支持完整的对话历史管理，能够基于前文内容进行连贯的多轮对话，提供更自然的交互体验。
  </Accordion>
</AccordionGroup>

***

## 调用示例

<Steps>
  <Step title="安装 SDK">
    <CodeGroup>
      ```bash Python theme={null}
      pip install openai
      ```

      ```bash Node.js theme={null}
      npm install openai
      ```
    </CodeGroup>
  </Step>

  <Step title="设置环境变量">
    ```bash theme={null}
    export OPENAI_BASE_URL=https://api.minimaxi.com/v1
    export OPENAI_API_KEY=${YOUR_API_KEY}
    ```
  </Step>

  <Step title="调用 M2-her">
    <CodeGroup>
      ```python Python theme={null}
      from openai import OpenAI

      client = OpenAI()

      response = client.chat.completions.create(
          model="M2-her",
          messages=[
              {
                  "role": "system",
                  "name": "AI助手",
                  "content": "你是一个友好、专业的AI助手"
              },
              {
                  "role": "user",
                  "name": "用户",
                  "content": "你好，请介绍一下你自己"
              }
          ],
          temperature=1.0,
          top_p=0.95,
          max_completion_tokens=2048
      )

      print(response.choices[0].message.content)
      ```

      ```javascript Node.js theme={null}
      import OpenAI from "openai";

      const client = new OpenAI();

      const response = await client.chat.completions.create({
        model: "M2-her",
        messages: [
          {
            role: "system",
            name: "AI助手",
            content: "你是一个友好、专业的AI助手"
          },
          {
            role: "user",
            name: "用户",
            content: "你好，请介绍一下你自己"
          }
        ],
        temperature: 1.0,
        top_p: 0.95,
        max_tokens: 2048
      });

      console.log(response.choices[0].message.content);
      ```

      ```bash cURL theme={null}
      curl https://api.minimaxi.com/v1/text/chatcompletion_v2 \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${YOUR_API_KEY}" \
        -d '{
          "model": "M2-her",
          "messages": [
            {
              "role": "system",
              "name": "AI助手",
              "content": "你是一个友好、专业的AI助手"
            },
            {
              "role": "user",
              "name": "用户",
              "content": "你好，请介绍一下你自己"
            }
          ],
          "temperature": 1.0,
          "top_p": 0.95,
          "max_completion_tokens": 2048
        }'
      ```
    </CodeGroup>
  </Step>
</Steps>

***

## 角色类型说明

M2-her 支持以下几种消息角色类型：

### 基础角色

| 角色类型        | 说明         | 使用场景               |
| :---------- | :--------- | :----------------- |
| `system`    | 设定模型的角色和行为 | 定义 AI 的身份、性格、知识范围等 |
| `user`      | 用户的输入      | 用户发送的消息            |
| `assistant` | 模型的历史回复    | AI 之前的回复，用于多轮对话    |

### 高级角色

| 角色类型                  | 说明         | 使用场景          |
| :-------------------- | :--------- | :------------ |
| `user_system`         | 设定用户的角色和人设 | 角色扮演场景中定义用户身份 |
| `group`               | 对话的名称      | 标识对话分组或场景名称   |
| `sample_message_user` | 示例的用户输入    | 提供用户消息的示例     |
| `sample_message_ai`   | 示例的模型输出    | 提供期望的 AI 回复示例 |

***

## 使用场景示例

### 场景 1：基础对话

```python theme={null}
messages = [
    {
        "role": "system",
        "content": "你是一个专业的编程助手"
    },
    {
        "role": "user",
        "content": "如何学习 Python？"
    }
]
```

### 场景 2：角色扮演对话

```python theme={null}
messages = [
    {
        "role": "system",
        "content": "你是《三国演义》中的诸葛亮，智慧、沉稳、善于谋略"
    },
    {
        "role": "user_system",
        "content": "你是一位来自现代的穿越者"
    },
    {
        "role": "group",
        "content": "三国时期的隆中对话"
    },
    {
        "role": "user",
        "content": "军师，我有一些现代的想法想和您探讨"
    }
]
```

### 场景 3：示例学习对话

```python theme={null}
messages = [
    {
        "role": "system",
        "content": "你是一个幽默风趣的聊天伙伴"
    },
    {
        "role": "sample_message_user",
        "content": "今天天气真好"
    },
    {
        "role": "sample_message_ai",
        "content": "是啊！阳光明媚的日子总让人心情愉悦，就像你的笑容一样灿烂~"
    },
    {
        "role": "user",
        "content": "明天准备去爬山"
    }
]
```

***

## 参数说明

### 核心参数

| 参数                      | 类型      | 默认值   | 说明                                           |
| :---------------------- | :------ | :---- | :------------------------------------------- |
| `model`                 | string  | -     | 模型名称，固定为 `M2-her`                            |
| `messages`              | array   | -     | 对话消息列表，详见 [API 参考](/api-reference/text-chat) |
| `temperature`           | number  | 1.0   | 温度系数，控制输出随机性                                 |
| `top_p`                 | number  | 0.95  | 采样策略参数                                       |
| `max_completion_tokens` | integer | -     | 生成内容的最大长度，上限 2048                            |
| `stream`                | boolean | false | 是否使用流式输出                                     |

***

## 最佳实践

<AccordionGroup>
  <Accordion title="合理设置角色">
    使用 `system` 定义 AI 的基本行为，使用 `user_system` 定义用户身份，可以让对话更加自然和符合场景设定。
  </Accordion>

  <Accordion title="提供示例对话">
    通过 `sample_message_user` 和 `sample_message_ai` 提供 1-3 个示例对话，可以有效引导模型的回复风格。
  </Accordion>

  <Accordion title="维护对话历史">
    保留完整的对话历史（包括 `user` 和 `assistant` 消息），让模型能够基于上下文进行连贯回复。
  </Accordion>

  <Accordion title="控制对话长度">
    根据场景需求设置合适的 `max_completion_tokens`，避免回复过长或被截断。
  </Accordion>
</AccordionGroup>

***

## 常见问题

<AccordionGroup>
  <Accordion title="如何实现多轮对话？">
    在每次请求中包含完整的对话历史，按时间顺序排列 `user` 和 `assistant` 消息。
  </Accordion>

  <Accordion title="user_system 和 system 有什么区别？">
    `system` 定义 AI 的角色，`user_system` 定义用户的角色。在角色扮演场景中，两者配合使用可以创建更丰富的对话体验。
  </Accordion>

  <Accordion title="示例消息会占用 token 吗？">
    是的，所有消息（包括示例消息）都会计入输入 token。建议提供 1-3 个精炼的示例即可。
  </Accordion>

  <Accordion title="是否支持图片输入？">
    M2-her 当前仅支持文本输入，不支持图文混合输入。
  </Accordion>
</AccordionGroup>

***

## 相关链接

<CardGroup cols={2}>
  <Card title="API 参考" icon="book-open" href="/api-reference/text-chat">
    查看完整的 API 接口文档
  </Card>

  <Card title="定价说明" icon="book-open" href="/guides/pricing-paygo#文本">
    了解 M2-her 的定价详情
  </Card>

  <Card title="错误码" icon="book-open" href="/api-reference/errorcode">
    查看 API 错误码说明
  </Card>

  <Card title="快速开始" icon="rocket" href="/guides/quickstart">
    快速上手 MiniMax API
  </Card>
</CardGroup>


---

## 6. 豆包 (火山引擎)
数分钟内完成你的首次 API 调用。

<columns>
<columnsItem zoneid="n3gHGWoYuw">


<card mode="container" href="https://www.volcengine.com/docs/82379/2272060" >

**快速入门(新手版)**
专为零基础用户设计的快速入门

</card>



</columnsItem>
<columnsItem zoneid="k9cxdr6qu9">


<card mode="container" href="https://console.volcengine.com/ark/region:ark+cn-beijing/experience" >

**体验中心**
“0”代码，交互式体验模型能力

</card>



</columnsItem>
<columnsItem zoneid="SF3U9awVi7">


<card mode="container" href="https://www.volcengine.com/docs/82379/1928261" >

**Coding Plan**
兼容主流 AI 工具，助力高效编码开发

</card>



</columnsItem>
</columns>

<span id="da0e9d90"></span>
# 1 获取并配置 API Key

1. 获取 API Key：访问[API Key 管理](https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey) ，创建你的 API Key。
2. 配置环境变量：在终端中运行下面命令（替换`your_api_key_here` 为你的方舟API Key），配置 API Key 到环境变量。
> 配置持久化环境变量方法参见 [环境变量配置指南](/docs/82379/1820161)。


```mixin-react
return (<Tabs>
<Tabs.TabPane title="MacOS" key="il3tCZmrrU"><RenderMd content={`\`\`\`Bash
export ARK_API_KEY="your_api_key_here"
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Linux" key="zumAJEh9nw"><RenderMd content={`\`\`\`Bash
export ARK_API_KEY="your_api_key_here"
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Windows_CMD" key="HJJ3oOxexr"><RenderMd content={`\`\`\`Bash
setx ARK_API_KEY "your_api_key_here"
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Windows_PowerShell" key="BYOrQfddA2"><RenderMd content={`\`\`\`PowerShell
$env:ARK_API_KEY = "your_api_key_here"
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


<span id="1008bfdb"></span>
# 2 开通模型服务
访问 [开通管理页面](https://console.volcengine.com/ark/region:ark+cn-beijing/openManagement) 开通模型服务。
<span id="b30fecf4"></span>
# 3 安装 SDK
安装官方或三方 SDK。

```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="ghJgzcFFtd"><RenderMd content={`> 运行环境中需安装 [Python](https://www.python.org/downloads/) 版本 3.7 或以上。

* 安装方舟 SDK：
   \`\`\`Bash
   pip install 'volcengine-python-sdk[ark]'
   \`\`\`
   
* 安装 OpenAI SDK：
   \`\`\`Bash
   pip install openai
   \`\`\`
   
`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="UmL12vRsq5"><RenderMd content={`> 环境中安装 [Go](https://golang.google.cn/doc/install) 版本 1.18 或以上。

在代码中通过下方方法引入 Go SDK
\`\`\`Go
import (
  "github.com/volcengine/volcengine-go-sdk"
)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="HcNsbPsCca"><RenderMd content={`> 环境中安装 [Java](https://www.java.com/en/download/help/index_installing.html) 版本 1.8 或以上。

在项目的\`pom.xml\`文件中添加以下依赖配置。
\`\`\`XML
<dependency>
  <groupId>com.volcengine</groupId>
  <artifactId>volcengine-java-sdk-ark-runtime</artifactId>
  <version>LATEST</version>
</dependency>
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="f97e77a7"></span>
# 4 发起 API 请求
以下按输入输出类别列举的典型任务，选择任意示例代码体验如何通过 API 调用及体验大模型及方舟平台能力。
<span id="b25b812a"></span>
## 文本生成
传入文本类信息给模型，进行问答、分析、改写、摘要、编程、翻译等任务，并返回文本结果。

<span aceTableMode="list" aceTableWidth="4,4"></span>
|输入 |输出预览 |
|---|---|
|Hello |* 思考：Got it, let's see. The user said "hello". I need to respond in a friendly and welcoming way. Since the system prompt mentions a professional but friendly tone, I should keep it natural. Maybe something like "Hello! How can I assist you today?" That's simple, polite, and open\-ended to encourage the user to share what they need help with.|\
| |* 回答：Hello! How can I assist you today? Whether you have a question, need help with something specific, or just want to chat, feel free to let me know. 😊 |


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="DL0pjVMJS2"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY'),
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input="hello", # Replace with your prompt
    # thinking=\{"type": "disabled"\}, #  Manually disable deep thinking
)
print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Curl" key="RzGtlZ62sW"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
  -H "Authorization: Bearer $ARK_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '\{
      "model": "doubao-seed-2-0-lite-260215",
      "input": "hello"
  \}'
\`\`\`


* 关闭深度思考：配置 \`"thinking":\{"type": "disabled"\}\`。
`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="TmR4PksT6T"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() \{
    client := arkruntime.NewClientWithApiKey(
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest\{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput\{Union: &responses.ResponsesInput_StringValue\{StringValue: "hello"\}\}, // Replace with your prompt
        // Thinking: &responses.ResponsesThinking\{Type: responses.ThinkingType_disabled.Enum()\}, // Manually disable deep thinking
    \})
    if err != nil \{
        fmt.Printf("response error: %v\\n", err)
        return
    \}
    fmt.Println(resp)
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="qPEauK5WeU"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;

public class demo \{
    public static void main(String[] args) \{
        String apiKey = System.getenv("ARK_API_KEY");
        // The base URL for model invocation
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().stringValue("hello").build()) // Replace with your prompt
                // .thinking(ResponsesThinking.builder().type(ResponsesConstants.THINKING_TYPE_DISABLED).build()) //  Manually disable deep thinking
                .build();

        ResponseObject resp = arkService.createResponse(request);
        System.out.println(resp);

        arkService.shutdownExecutor();
    \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="FYpVgLp3x3"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY'),
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input="hello", # Replace with your prompt
    extra_body=\{
        # "thinking": \{"type": "disabled"\}, #  Manually disable deep thinking
    \},
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* [文本生成](/docs/82379/1399009)：文本生成使用指南。
* [深度思考](/docs/82379/1956279)：深度思考能力使用指南。
* [迁移至 Responses API](/docs/82379/1585128)：新用户推荐，更简洁的上下文管理能力、强大的工具调用能力。
* [Chat API](https://www.volcengine.com/docs/82379/1494384)：存量业务迭代推荐，广泛使用的 API。

<span id="efbfe823"></span>
## 多模态理解
传入图片、视频、PDF文件给模型，进行分析、内容审核、问答、视觉定位等基于多模态理解相关任务，并返回文本结果。

<span aceTableMode="list" aceTableWidth="4,4"></span>
|输入 |输出预览 |
|---|---|
|<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/a31c2edfbe844461a43f5e8f74fbcce4~tplv-goo7wpa0wc-image.image =275x) </span>|* 思考：用户现在需要找支持输入图片的模型系列，看表格里的输入列中的图像列，哪个模型对应的图像输入是√。看表格，Doubao\-1.5\-vision那一行的输入图像列是√，其他两个Doubao\-1.5\-pro和lite的输入图像都是×，所以答案是Doubao\-1.5\-vision。|\
|> 支持输入图片的模型系列是哪个？ |* 回答：支持输入图片的模型系列是Doubao\-1.5\-vision |


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="fc7Es2qqKa"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY'),
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        \{
            "role": "user",
            "content": [
                \{
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
                \},
                \{
                    "type": "input_text",
                    "text": "支持输入图片的模型系列是哪个？"
                \},
            ],
        \}
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Curl" key="VEMXiaublm"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
-H "Authorization: Bearer $ARK_API_KEY" \\
-H 'Content-Type: application/json' \\
-d '\{
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
        \{
            "role": "user",
            "content": [
                \{
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
                \},
                \{
                    "type": "input_text",
                    "text": "支持输入图片的模型系列是哪个？"
                \}
            ]
        \}
    ]
\}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="pmBIx12mlV"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"    
    "github.com/samber/lo"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() \{
    client := arkruntime.NewClientWithApiKey(
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    inputMessage := &responses.ItemInputMessage\{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem\{
            \{
                Union: &responses.ContentItem_Image\{
                    Image: &responses.ContentItemImage\{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"),
                    \},
                \},
            \},
            \{
                Union: &responses.ContentItem_Text\{
                    Text: &responses.ContentItemText\{
                        Type: responses.ContentItemType_input_text,
                        Text: "支持输入图片的模型系列是哪个？",
                    \},
                \},
            \},
        \},
    \}

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest\{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput\{
            Union: &responses.ResponsesInput_ListValue\{
                ListValue: &responses.InputItemList\{ListValue: []*responses.InputItem\{\{
                    Union: &responses.InputItem_InputMessage\{
                        InputMessage: inputMessage,
                    \},
                \}\}\},
            \},
        \},
    \})
    if err != nil \{
        fmt.Printf("response error: %v\\n", err)
        return
    \}
    fmt.Println(resp)
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="uIZR7TzQMb"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.content.*;
import com.volcengine.ark.runtime.model.responses.item.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;

public class demo \{
  public static void main(String[] args) \{
    String apiKey = System.getenv("ARK_API_KEY");
    ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3")
        .build();

    CreateResponsesRequest request = CreateResponsesRequest.builder()
        .model("doubao-seed-2-0-lite-260215")
        .input(ResponsesInput.builder().addListItem(
            ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                MessageContent.builder()
                    .addListItem(InputContentItemImage.builder()
                        .imageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png").build())
                    .addListItem(InputContentItemText.builder().text("支持输入图片的模型系列是哪个？").build())
                    .build())
                .build())
            .build())
        .build();
    ResponseObject resp = arkService.createResponse(request);
    System.out.println(resp);

    arkService.shutdownExecutor();
  \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="A99F6veUWE"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY'),
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        \{
            "role": "user",
            "content": [
                \{
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
                \},
                \{
                    "type": "input_text",
                    "text": "支持输入图片的模型系列是哪个？"
                \},
            ],
        \}
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* [多模态理解](/docs/82379/1958521)：多模态理解详细使用指南。
* [视觉定位 Grounding](/docs/82379/1616136)：图片中找到对应目标并返回坐标任务。
* [GUI 任务处理](/docs/82379/1584296)：在计算机/移动设备中完成自动化任务。
* [文件输入(File API)](/docs/82379/1885708)：传入图片、视频、文档接口。

<span id="d481ca5b"></span>
## 图片生成
传入图片、文字给模型，进行以下场景&任务：

* 广告、海报、组图等图片生成；
* 增改元素、颜色更换等图片编辑；
* 油墨、水墨等风格切换。


<span aceTableMode="list" aceTableWidth="4,4"></span>
|提示词 |输出预览 |
|---|---|
|充满活力的特写编辑肖像，模特眼神犀利，头戴雕塑感帽子，色彩拼接丰富，眼部焦点锐利，景深较浅，具有Vogue杂志封面的美学风格，采用中画幅拍摄，工作室灯光效果强烈。 |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/00fb66006eb84b16965b620b6e1f2d78~tplv-goo7wpa0wc-image.image =275x) </span> |


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="kfEYmE6GKD"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]' 
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)
 
imagesResponse = client.images.generate( 
    # Replace with Model ID
    model="doubao-seedream-5-0-260128",
    prompt="充满活力的特写编辑肖像，模特眼神犀利，头戴雕塑感帽子，色彩拼接丰富，眼部焦点锐利，景深较浅，具有Vogue杂志封面的美学风格，采用中画幅拍摄，工作室灯光效果强烈。",
    size="2K",
    response_format="url",
    watermark=False
) 
 
print(imagesResponse.data[0].url)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Curl" key="OPMtOX49Ig"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/images/generations \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $ARK_API_KEY" \\
  -d '\{
    "model": "doubao-seedream-5-0-260128",
    "prompt": "充满活力的特写编辑肖像，模特眼神犀利，头戴雕塑感帽子，色彩拼接丰富，眼部焦点锐利，景深较浅，具有Vogue杂志封面的美学风格，采用中画幅拍摄，工作室灯光效果强烈。",
    "size": "2K",
    "watermark": false
\}'
\`\`\`


* 您可按需替换 Model ID。Model ID 查询见 [模型列表](/docs/82379/1330310)。
`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="znTga9bxHn"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.images.generation.*;
import com.volcengine.ark.runtime.service.ArkService;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import java.util.concurrent.TimeUnit;

public class ImageGenerationsExample \{ 
    public static void main(String[] args) \{
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        String apiKey = System.getenv("ARK_API_KEY");
        ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        ArkService service = ArkService.builder()
                .baseUrl("https://ark.cn-beijing.volces.com/api/v3") // The base URL for model invocation
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .apiKey(apiKey)
                .build();
                
        GenerateImagesRequest generateRequest = GenerateImagesRequest.builder()
                .model("doubao-seedream-5-0-260128") // Replace with Model ID
                .prompt("充满活力的特写编辑肖像，模特眼神犀利，头戴雕塑感帽子，色彩拼接丰富，眼部焦点锐利，景深较浅，具有Vogue杂志封面的美学风格，采用中画幅拍摄，工作室灯光效果强烈。")
                .size("2K")
                .sequentialImageGeneration("disabled")
                .responseFormat(ResponseFormat.Url)
                .stream(false)
                .watermark(false)
                .build();
        ImagesResponse imagesResponse = service.generateImages(generateRequest);
        System.out.println(imagesResponse.getData().get(0).getUrl());

        service.shutdownExecutor();
    \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="LmeqT9EXM2"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "strings"    
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() \{
    client := arkruntime.NewClientWithApiKey(        
        os.Getenv("ARK_API_KEY"), // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey        
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"), // The base URL for model invocation
    )    
    ctx := context.Background()

    generateReq := model.GenerateImagesRequest\{
       Model:          "doubao-seedream-5-0-260128", // Replace with Model ID
       Prompt:         "充满活力的特写编辑肖像，模特眼神犀利，头戴雕塑感帽子，色彩拼接丰富，眼部焦点锐利，景深较浅，具有Vogue杂志封面的美学风格，采用中画幅拍摄，工作室灯光效果强烈。",
       Size:           volcengine.String("2K"),
       ResponseFormat: volcengine.String(model.GenerateImagesResponseFormatURL),
       Watermark:      volcengine.Bool(false),
    \}

    imagesResponse, err := client.GenerateImages(ctx, generateReq)
    if err != nil \{
       fmt.Printf("generate images error: %v\\n", err)
       return
    \}

    fmt.Printf("%s\\n", *imagesResponse.Data[0].Url)
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI" key="dzJJoYbc4A"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

client = OpenAI(     
    base_url="https://ark.cn-beijing.volces.com/api/v3", # The base URL for model invocation    
    api_key=os.getenv('ARK_API_KEY'),  # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
) 
 
imagesResponse = client.images.generate( 
    # Replace with Model ID
    model="doubao-seedream-5-0-260128",
    prompt="充满活力的特写编辑肖像，模特眼神犀利，头戴雕塑感帽子，色彩拼接丰富，眼部焦点锐利，景深较浅，具有Vogue杂志封面的美学风格，采用中画幅拍摄，工作室灯光效果强烈。",
    size="2K",
    response_format="url",
    extra_body=\{
        "watermark": False,
    \},
) 
 
print(imagesResponse.data[0].url)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* [Seedream 4.0-5.0 教程](/docs/82379/1824121)：主流生图模型能力以及如何通过 API 调用。
* [Seedream 4.0-5.0 提示词指南](/docs/82379/1829186)：使用生图模型时，如何编写提示词。

<span id="18692b80"></span>
## 视频生成
通过文本描述、图像素材、视频素材，快速生成高质量、风格多样的视频内容。

<span aceTableMode="list" aceTableWidth="4,4"></span>
|提示词 |输出画面预览 |
|---|---|
|一位身穿绿色亮片礼服的女性站在粉红色背景前，周围飘落着五彩斑斓的彩纸 |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/aae3d0c636954bdd9e66e7a23e98c480~tplv-goo7wpa0wc-image.image =275x) </span> |


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="IVFt2AaORq"><RenderMd content={`\`\`\`Python
import os
import time  
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(    
    base_url="https://ark.cn-beijing.volces.com/api/v3", # The base URL for model invocation    
    api_key=os.environ.get("ARK_API_KEY"), # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
)

if __name__ == "__main__":
    print("----- create request -----")
    create_result = client.content_generation.tasks.create(
        model="doubao-seedance-2-0-260128", # Replace with Model ID 
        content=[
            \{
                # Combination of text prompt and parameters
                "type": "text",
                "text": "一位身穿绿色亮片礼服的女性站在粉红色背景前，周围飘落着五彩斑斓的彩纸 --wm true --dur 5"
            \}
        ]
    )
    print(create_result)

    # Polling query section
    print("----- polling task status -----")
    task_id = create_result.id
    while True:
        get_result = client.content_generation.tasks.get(task_id=task_id)
        status = get_result.status
        if status == "succeeded":
            print("----- task succeeded -----")
            print(get_result)
            break
        elif status == "failed":
            print("----- task failed -----")
            print(f"Error: \{get_result.error\}")
            break
        else:
            print(f"Current status: \{status\}, Retrying after 3 seconds...")
            time.sleep(3)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="b3xr4QBYsA"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.volcengine.ark.runtime.model.content.generation.*;
import com.volcengine.ark.runtime.model.content.generation.CreateContentGenerationTaskRequest.Content;
import com.volcengine.ark.runtime.service.ArkService;

public class ContentGenerationTaskExample \{
  public static void main(String[] args) \{
    String apiKey = System.getenv("ARK_API_KEY");
    ArkService service = ArkService.builder()
        .baseUrl("https://ark.cn-beijing.volces.com/api/v3") // The base URL for model invocation
        .apiKey(apiKey)
        .build();

    System.out.println("----- create request -----");
    List<Content> contents = new ArrayList<>();
    contents.add(Content.builder()
        .type("text")
        .text("一位身穿绿色亮片礼服的女性站在粉红色背景前，周围飘落着五彩斑斓的彩纸 --wm true --dur 5")
        .build());

    // Create a video generation task
    CreateContentGenerationTaskRequest createRequest = CreateContentGenerationTaskRequest.builder()
        .model("doubao-seedance-2-0-260128") // Replace with Model ID
        .content(contents)
        .build();

    CreateContentGenerationTaskResult createResult = service.createContentGenerationTask(createRequest);
    System.out.println(createResult);

    // Get the details of the task
    String taskId = createResult.getId();
    GetContentGenerationTaskRequest getRequest = GetContentGenerationTaskRequest.builder()
        .taskId(taskId)
        .build();

    System.out.println("----- polling task status -----");
    while (true) \{
      try \{
        GetContentGenerationTaskResponse getResponse = service.getContentGenerationTask(getRequest);
        String status = getResponse.getStatus();
        if ("succeeded".equalsIgnoreCase(status)) \{
          System.out.println("----- task succeeded -----");
          System.out.println(getResponse);
          service.shutdownExecutor();
          break;
        \} else if ("failed".equalsIgnoreCase(status)) \{
          System.out.println("----- task failed -----");
          System.out.println("Error: " + getResponse.getStatus());
          service.shutdownExecutor();
          break;
        \} else \{
          System.out.printf("Current status: %s, Retrying in 3 seconds...\\n", status);
          TimeUnit.SECONDS.sleep(3);
        \}
      \} catch (InterruptedException ie) \{
        Thread.currentThread().interrupt();
        System.err.println("Polling interrupted");
        service.shutdownExecutor();
        break;
      \}
    \}
  \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="eZJksLea2U"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "time"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() \{
    client := arkruntime.NewClientWithApiKey(        
        os.Getenv("ARK_API_KEY"), // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey        
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"), // The base URL for model invocation
    )
    ctx := context.Background()
    // Replace with Model ID
    modelEp := "doubao-seedance-2-0-260128"

    fmt.Println("----- create request -----")
    createReq := model.CreateContentGenerationTaskRequest\{
        Model: modelEp,
        Content: []*model.CreateContentGenerationContentItem\{
            \{
                Type: model.ContentGenerationContentItemTypeText,
                Text: volcengine.String("一位身穿绿色亮片礼服的女性站在粉红色背景前，周围飘落着五彩斑斓的彩纸 --wm true --dur 5"),
            \},
        \},
    \}
    createResp, err := client.CreateContentGenerationTask(ctx, createReq)
    if err != nil \{
        fmt.Printf("create content generation error: %v", err)
        return
    \}
    taskID := createResp.ID
    fmt.Printf("Task Created with ID: %s", taskID)

    // Polling query section
    fmt.Println("----- polling task status -----")
    for \{
        getReq := model.GetContentGenerationTaskRequest\{ID: taskID\}
        getResp, err := client.GetContentGenerationTask(ctx, getReq)
        if err != nil \{
            fmt.Printf("get content generation task error: %v", err)
            return
        \}

        status := getResp.Status
        if status == "succeeded" \{
            fmt.Println("----- task succeeded -----")
            fmt.Printf("Task ID: %s \\n", getResp.ID)
            fmt.Printf("Model: %s \\n", getResp.Model)
            fmt.Printf("Video URL: %s \\n", getResp.Content.VideoURL)
            fmt.Printf("Completion Tokens: %d \\n", getResp.Usage.CompletionTokens)
            fmt.Printf("Created At: %d, Updated At: %d", getResp.CreatedAt, getResp.UpdatedAt)
            return
        \} else if status == "failed" \{
            fmt.Println("----- task failed -----")
            if getResp.Error != nil \{
                fmt.Printf("Error Code: %s, Message: %s", getResp.Error.Code, getResp.Error.Message)
            \}
            return
        \} else \{
            fmt.Printf("Current status: %s, Retrying in 3 seconds... \\n", status)
            time.Sleep(3 * time.Second)
        \}
    \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* [视频生成教程](/docs/82379/2298881)：学习如何使用模型的视频生成能力，包括文本生成视频、首尾帧生视频、首帧生成视频等。
* [Seedance-1.0-pro&pro-fast 提示词指南](/docs/82379/1631633)：使用生视频模型时，如何编写提示词。

<span id="086a3233"></span>
## 工具使用
通过工具/插件让模型具体读取外部数据及函数的能力，包括

* 内置工具：联网搜索、图片处理、知识库检索等已集成至方舟平台的工具。
* 三方工具：兼容MCP 的三方工具。
* 自定义工具：您自行定义及开发的工具。


<span aceTableMode="list" aceTableWidth="4,4"></span>
|输入 |输出预览 |
|---|---|
|What's the weather like in Beijing? |According to the latest weather data as of March 10, 2026, the current weather in Beijing is sunny with a gentle wind (less than level 3). The temperature around 11:30 AM is approximately 9°C, and it is expected to reach a high of 12°C during the day. The weather will remain clear at night with a low temperature of 1°C.|\
| |**Source**: Weather forecasts updated on March 10, 2026, from the Central Meteorological Observatory.|\
| |**Note: Data is accurate as of the latest available update at 05:30 AM on March 10.**  |


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="upOkArkAPh"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

client = Ark(
    base_url="https://ark.cn-beijing.volces.com/api/v3",
    api_key=os.getenv("ARK_API_KEY"),
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[\{"role": "user", "content": "What's the weather like in Beijing?"\}],
    tools=[
        \{
            "type": "web_search",
            "max_keyword": 2,
        \}
    ],
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Curl" key="DyNIhY1ZUO"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
-H "Authorization: Bearer $ARK_API_KEY" \\
-H 'Content-Type: application/json' \\
-d '\{
    "model": "doubao-seed-2-0-lite-260215",
    "stream": true,
    "tools": [
        \{
            "type": "web_search",
            "max_keyword": 3
        \}
    ],
    "input": [
        \{
            "role": "user",
            "content": [
                \{
                    "type": "input_text",
                    "text": "What is the weather like in Beijing?"
                \}
            ]
        \}
    ]
\}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="R54PzhYsfz"><RenderMd content={`\`\`\`Java
package com.ark.sample;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.ark.runtime.model.responses.item.*;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.tool.*;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.Arrays;
import java.util.List;

public class demo \{
    public static ObjectMapper om = new ObjectMapper();

    public demo() throws JsonProcessingException \{
    \}

    public static List<ResponsesTool> buildTools() \{
        ToolWebSearch t = ToolWebSearch.builder().build();
        System.out.println(Arrays.asList(t));
        return Arrays.asList(t);
    \}

    public static void main(String[] args) throws JsonProcessingException \{
        String apiKey = System.getenv("ARK_API_KEY");

        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();
        CreateResponsesRequest req = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemText.builder().text("What's the weather like in Beijing?").build())
                                        .build()
                        ).build()
                ).build())
                .tools(buildTools())
                .build();
        ResponseObject resp = arkService.createResponse(req);
        System.out.println(resp);

        arkService.shutdownExecutor();
    \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="UdEPFdAKGz"><RenderMd content={`\`\`\`Go
package main
import (
  "context"
  "fmt"
  "os"
  "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
  "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() \{
  client := arkruntime.NewClientWithApiKey(
    os.Getenv("ARK_API_KEY"),
    arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"), // The base URL for model invocation
  )
  ctx := context.Background()
  maxToolCalls := int64(1) // Limit the number of tool calls, adjust it according to your needs.
  
  inputMessage := &responses.ItemInputMessage\{
    Role: responses.MessageRole_user,
    Content: []*responses.ContentItem\{
      \{
        Union: &responses.ContentItem_Text\{
          Text: &responses.ContentItemText\{
            Type: responses.ContentItemType_input_text,
            Text: "What's the weather like in Beijing?",
          \},
        \},
      \},
    \},
  \}

  req := &responses.ResponsesRequest\{
    Model: "doubao-seed-2-0-lite-260215",
    Input: &responses.ResponsesInput\{
      Union: &responses.ResponsesInput_ListValue\{
        ListValue: &responses.InputItemList\{ListValue: []*responses.InputItem\{\{
          Union: &responses.InputItem_InputMessage\{
            InputMessage: inputMessage,
          \},
        \}\}\}\},
    \},
    Tools: []*responses.ResponsesTool\{
      \{
        Union: &responses.ResponsesTool_ToolWebSearch\{
          ToolWebSearch: &responses.ToolWebSearch\{
            Type: responses.ToolType_web_search,
          \},
        \},
      \},
    \},
    MaxToolCalls: &maxToolCalls,
  \}

  resp, err := client.CreateResponses(ctx, req)
  if err != nil \{
    fmt.Printf("Error: %v\\n", err)
    os.Exit(1)
  \}

  fmt.Printf("Response: %v\\n", resp)
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="I686LWSHRE"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

client = OpenAI(
    base_url="https://ark.cn-beijing.volces.com/api/v3",
    api_key=os.getenv("ARK_API_KEY"),
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[\{"role": "user", "content": "What's the weather like in Beijing?"\}],
    tools=[
        \{
            "type": "web_search",
            "max_keyword": 2,
        \}
    ],
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* [工具调用](/docs/82379/1958524)：学习如何让模型使用内置工具，如网页搜索、知识库检索、豆包助手等能力。
* [Function Calling（函数调用）](/docs/82379/1262342)：学习如何让模型调用自定义的工具。
* [云部署 MCP / Remote MCP](/docs/82379/1827534)：学习如何让模型使用 MCP 服务。

<span id="ffac0939"></span>
# 5 下一步
现在你已经完成了首次方舟模型服务的 API 调用，你可以探索模型的更多能力，包括：

* [模型列表](/docs/82379/1330310)：快速浏览方舟提供的模型全集以及各个模型所具备的能力，快速根据你的实际场景匹配到合适的模型。



方舟提供多种模型供您使用。您可浏览所有模型，比较它们的能力。
<span id="b4dabf9a"></span>
# 模型推荐

<columns>
<columnsItem zoneid="IZy0G5WdVD">


<card mode="container" href="https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-pro" img="https://ark-project.tos-cn-beijing.volces.com/doc_model/banner_thinking.png" >

**Doubao Seed 2.0**
豆包旗舰级 Agent 通用模型
面向 Agent 时代的复杂推理与长链路任务执行场景

</card>



</columnsItem>
<columnsItem zoneid="OyxdBtvtgW">


<card mode="container" href="https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedance-2-0" img="https://ark-project.tos-cn-beijing.volces.com/doc_model/banner_video_generation.png" >

**Doubao Seedance 2.0**
豆包最强视频生成模型
极致拟真的视听稳定，赋予创作者如同导演般的掌控权

</card>



</columnsItem>
<columnsItem zoneid="CoPxuy5C0b">


<card mode="container" href="https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedream-5-0" img="https://ark-project.tos-cn-beijing.volces.com/doc_model/banner_image_generation.png" >

**Doubao Seedream 5.0**
豆包最强图片生成模型
搭载联网检索，增强知识广度、参考一致性及专业场景生成质量

</card>



</columnsItem>
</columns>

<span id="43b6e6a1"></span>
# 深度思考能力
教程: [深度思考](/docs/82379/1449737) | API: [Responses API](https://www.volcengine.com/docs/82379/1569618)、[Chat API](https://www.volcengine.com/docs/82379/1494384)
<span id="050959ae"></span>
## 推荐模型

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |能力支持 |长度限制（token） |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-2-0-lite-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-mini-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-pro-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-pro) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-lite-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用|最大思维链: 128k | |\
| |结构化输出 | | |
|[doubao-seed-2-0-mini-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用|最大思维链: 128k | |\
| |结构化输出 | | |
|[doubao-seed-2-0-code-preview-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-code) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |

<span id="6b00b2c3"></span>
## 往期模型

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |能力支持 |长度限制（token） |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-1-8-251228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-8) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出 | | |
|[doubao-seed-code-preview-251028](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-code) |深度思考|上下文窗口: 256k|最大 RPM: 5000|\
| |> 编程场景增强|最大输入: 224k|最大 TPM: 1200000 |\
| ||[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |文本生成|最大思维链: 32k | |\
| |多模态理解| | |\
| |工具调用 | | |
|[doubao-seed-1-6-lite-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用 |最大思维链: 32k | |
|[doubao-seed-1-6-flash-250828](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |视觉定位|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |多模态理解|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-vision-250815](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-vision) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |GUI任务|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出 | | |
|[doubao-seed-1-6-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出 | | |
|[doubao-seed-1-6-vision-250815](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-vision) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |GUI任务|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-flash-250828](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |视觉定位|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |多模态理解|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-flash-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |视觉定位|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |多模态理解|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[glm-4-7-251222](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=glm-4-7) |深度思考|上下文窗口: 200k|最大 RPM: 15000|\
| |文本生成|最大输入: 200k|最大 TPM: 1500000 |\
| |工具调用 |[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| | |最大思维链: 128k | |
|[deepseek-v3-2-251201](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-2) |深度思考|上下文窗口: 128k|最大 RPM: 15000|\
| |文本生成|最大输入: 128k|最大 TPM: 1500000 |\
| |工具调用 |[最大回答(默认 4k)](https://www.volcengine.com/docs/82379/1399009#0001): 32k| |\
| | |最大思维链: 32k | |
|[deepseek-v3-1-terminus](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-1) |深度思考|上下文窗口: 128k|最大 RPM: 30000|\
| |文本生成|最大输入: 96k|最大 TPM: 5000000 |\
| |工具调用 |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)16k| |\
| | |最大思维链: 32k | |
|[deepseek-r1-250528](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-r1) |深度思考|上下文窗口: 128k|最大 RPM: 30000|\
| |工具调用 |最大输入: 96k|最大 TPM: 5000000 |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| | |最大思维链: 32k | |

<span id="32e9e053"></span>
# 文本生成能力
教程: [文本生成](/docs/82379/1399009) | API: [Responses API](https://www.volcengine.com/docs/82379/1569618)、[Chat API](https://www.volcengine.com/docs/82379/1494384)
:::warning
此处特指支持无深度思考的文本生成任务的模型。
:::
<span id="5fd8d8b1"></span>
## 推荐模型

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |**能力支持** |**长度限制（token）**  |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-2-0-lite-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-mini-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-pro-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-pro) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-lite-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用|最大思维链: 128k | |\
| |结构化输出 | | |
|[doubao-seed-2-0-mini-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用|最大思维链: 128k | |\
| |结构化输出 | | |
|[doubao-seed-2-0-code-preview-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-code) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-character-251128](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-character) |文本生成|上下文窗口: 128k|最大 RPM: 30000|\
| |工具调用 |最大输入: 96k|最大 TPM: 5000000 |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k | |

<span id="af1b9bf4"></span>
## **往期模型**

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |**能力支持** |**长度限制（token）**  |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-1-8-251228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-8) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出 | | |
|[doubao-seed-code-preview-251028](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-code) |深度思考|上下文窗口: 256k|最大 RPM: 5000|\
| |文本生成|最大输入: 224k|最大 TPM: 1200000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用 |最大思维链: 32k | |
|[doubao-seed-1-6-lite-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用 |最大思维链: 32k | |
|[doubao-seed-1-6-flash-250828](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |视觉定位|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |多模态理解|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-translation-250915](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-translation) |文本生成|上下文窗口: 4k|最大 RPM: 5000|\
| |> 翻译增强 |最大输入: 1k|500000 TPM |\
| | |[最大回答(默认 3k):](https://www.volcengine.com/docs/82379/1399009#0001)3k| |\
| | |最大思维链: \- | |
|[doubao-seed-1-6-vision-250815](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-vision) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |GUI 任务处理|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出 | | |
|[doubao-seed-1-6-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出 | | |
|[doubao-seed-1-6-flash-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |视觉定位|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |多模态理解|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-1-5-pro-32k-250115](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |文本生成|上下文窗口: 128k|最大 RPM: 30000|\
| |工具调用 |最大输入: \-|最大 TPM: 5000000 |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)16k| |\
| | |最大思维链: \- | |
|[doubao-1-5-pro-32k-character-250715](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |文本生成|上下文窗口: 32k|^^|\
| |> 角色扮演场景增强 |最大输入: \-| |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)12k| |\
| | |最大思维链: \- | |
|[doubao-1-5-pro-32k-character-250228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |文本生成|上下文窗口: 32k|^^|\
| |> 角色扮演场景增强 |最大输入: \-| |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)12k| |\
| | |最大思维链: \- | |
|[doubao-1-5-lite-32k-250115](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-lite-32k) |文本生成|上下文窗口: 32k|最大 RPM: 30000|\
| |工具调用 |最大输入: \-|最大 TPM: 5000000 |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)12k| |\
| | |最大思维链: \- | |
|[glm-4-7-251222](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=glm-4-7) |深度思考|上下文窗口: 200k|最大 RPM: 15000|\
| |文本生成|最大输入: 200k|最大 TPM: 1500000 |\
| |工具调用 |[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| | |最大思维链: 128k | |
|[deepseek-v3-2-251201](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-2) |深度思考|上下文窗口: 128k|最大 RPM: 15000|\
| |文本生成|最大输入: 128k|最大 TPM: 1500000 |\
| |工具调用 |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| | |最大思维链: 32k | |
|[deepseek-v3-1-terminus](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-1) |深度思考|上下文窗口: 128k|最大 RPM: 30000|\
| |文本生成|最大输入: 96k|最大 TPM: 5000000 |\
| |工具调用 |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| | |最大思维链: 32k | |
|[deepseek-v3-250324](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3) |文本生成|上下文窗口: 128k|最大 RPM: 30000|\
| |工具调用 |最大输入: \-|最大 TPM: 5000000 |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)16k| |\
| | |最大思维链: \- | |

<span id="76df97c8"></span>
# 视觉理解能力
教程: [图片理解](/docs/82379/1362931)、 [视频理解](/docs/82379/1895586)、[文档理解](/docs/82379/1902647) | API: [Responses API](https://www.volcengine.com/docs/82379/1569618)、[Chat API](https://www.volcengine.com/docs/82379/1494384)
<span id="a33eb670"></span>
## 推荐模型

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |**能力支持** |**长度限制（token）**  |限流 |
|---|---|---|---|
|[doubao-seed-2-0-lite-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-mini-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-pro-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-pro) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |视觉定位|最大思维链: 128k | |\
| |工具调用 | | |
|[doubao-seed-2-0-lite-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |视觉定位|最大思维链: 128k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-2-0-mini-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |视觉定位|最大思维链: 128k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-2-0-code-preview-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-code) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |视觉定位|最大思维链: 128k | |\
| |工具调用 | | |

<span id="d6041866"></span>
## **往期模型**

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |**能力支持** |长度限制（token） |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-1-8-251228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-8) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |视觉定位|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-vision-250815](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-vision) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |视觉定位|最大思维链: 32k | |\
| |GUI 任务处理| | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-code-preview-251028](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-code) |深度思考|上下文窗口: 256k|最大 RPM: 5000|\
| |文本生成|最大输入: 224k|最大 TPM: 1200000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |视觉定位|最大思维链: 32k | |\
| |工具调用 | | |
|[doubao-seed-1-6-lite-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |视觉定位|最大思维链: 32k | |\
| |工具调用 | | |
|[doubao-seed-1-6-flash-250828](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |视觉定位|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |视觉定位|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |视觉定位|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-seed-1-6-flash-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |视觉定位|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |多模态理解|最大思维链: 32k | |\
| |视觉定位| | |\
| |工具调用| | |\
| |结构化输出 | | |
|[doubao-1-5-vision-pro-32k-250115](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-vision-pro-32k) |图片理解|上下文窗口: 32k|最大 RPM: 30000|\
| |工具调用 |最大输入: \-|最大 TPM: 5000000 |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)12k| |\
| | |最大思维链: \- | |

<span id="9619c0ba"></span>
# 音频理解能力
教程: [音频理解](/docs/82379/2377589) | API: [Responses API](https://www.volcengine.com/docs/82379/1569618)、[Chat API](https://www.volcengine.com/docs/82379/1494384)
<span id="7f07256a"></span>
## 推荐模型

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |**能力支持** |**长度限制（token）**  |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-2-0-lite-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |
|[doubao-seed-2-0-mini-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用 |最大思维链: 128k | |

<span id="42df3eb4"></span>
# GUI 任务处理能力
教程: [GUI 任务处理](/docs/82379/1584296) | API: [Responses API](https://www.volcengine.com/docs/82379/1569618)、[Chat API](https://www.volcengine.com/docs/82379/1494384)
<span id="99f6f6b8"></span>
## 推荐模型

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |**能力支持** |**长度限制（token）**  |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-1-6-vision-250815](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-vision) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |GUI 任务处理|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出 | | |

<span id="f44ceef7"></span>
# 工具调用能力

<span aceTableMode="list" aceTableWidth="2,2,1,1,1,1,1"></span>
|**模型 ID（Model ID）**  |**函数调用**|**知识库**|**MCP**|**联网内容插件**|**图像处理**|**豆包助手**|\
| |[Responses API](https://www.volcengine.com/docs/82379/1569618) & [Chat API](https://www.volcengine.com/docs/82379/1494384?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |
|---|---|---|---|---|---|---|
|[doubao-seed-2-0-lite-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-2-0-mini-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-2-0-pro-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-pro) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-2-0-lite-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-2-0-mini-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-2-0-code-preview-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-code) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-1-8-251228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-8) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-character-251128](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-character) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-code-preview-251028](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-code) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-1-6-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-1-6-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-1-6-flash-250828](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-1-6-flash-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-1-6-lite-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-lite) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-seed-1-6-vision-250815](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-vision) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-1-5-pro-32k-250115](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-1-5-pro-32k-character-250715](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-1-5-pro-32k-character-250228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-1-5-vision-pro-32k-250115](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-vision-pro-32k) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[doubao-1-5-lite-32k-250115](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-lite-32k) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[glm-4-7-251222](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=glm-4-7) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[glm-4-5-air](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=glm-4-5-air) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[deepseek-v3-2-251201](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-2) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[deepseek-v3-1-terminus](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-1) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |
|[deepseek-v3-250324](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[deepseek-r1-250528](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-r1) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[qwen3-32b-20250429](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=qwen3-32b) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[qwen3-14b-20250429](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=qwen3-14b) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[qwen3-8b-20250429](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=qwen3-8b) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[qwen3-0-6b-20250429](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=qwen3-0-6b) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |
|[qwen2-5-72b-20240919](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=qwen2-5-72b) |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/ee51ce32c1914aed81ff95080bb7db1d~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/f359753773c94d97885008ca1223c9bc~tplv-goo7wpa0wc-image.image =20x) </span> |

相关教程：

* [Function Calling（函数调用）](/docs/82379/1262342)
* [私域知识库搜索 Knowledge Search](/docs/82379/1873396)
* [云部署 MCP / Remote MCP](/docs/82379/1827534)
* [Web Search（联网内容插件）](/docs/82379/1756990)
* [图像处理 Image Process](/docs/82379/1798161)
* [豆包助手](/docs/82379/1978533)（Beta）

<span id="ed095742"></span>
# 上下文缓存能力
介绍: [原理及选型](/docs/82379/1398933) | API: [Responses API](https://www.volcengine.com/docs/82379/1569618) 、 [Context Create API](https://www.volcengine.com/docs/82379/1528789)、[Chat API](https://www.volcengine.com/docs/82379/1494384)、[Batch API](https://www.volcengine.com/docs/82379/2123267)
<span id="8a26d5c8"></span>
## 推荐模型

<span aceTableMode="list" aceTableWidth="3,2,3"></span>
|模型ID（Model ID） |隐式缓存 |显式缓存 |
|---|---|---|
|[doubao-seed-2-0-lite-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| |[Chat API](https://www.volcengine.com/docs/82379/1494384?lang=zh) |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-2-0-mini-260428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| |[Chat API](https://www.volcengine.com/docs/82379/1494384?lang=zh) |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-2-0-pro-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-pro) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| |[Chat API](https://www.volcengine.com/docs/82379/1494384?lang=zh) |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-2-0-lite-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| |[Chat API](https://www.volcengine.com/docs/82379/1494384?lang=zh) |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-2-0-mini-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| |[Chat API](https://www.volcengine.com/docs/82379/1494384?lang=zh) |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-2-0-code-preview-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-code) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| |[Chat API](https://www.volcengine.com/docs/82379/1494384?lang=zh) |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-character-251128](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-character) |– |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |

<span id="45477f0f"></span>
## 往期模型

<span aceTableMode="list" aceTableWidth="3,2,3"></span>
|模型ID（Model ID） |隐式缓存 |显式缓存 |
|---|---|---|
|[doubao-seed-1-8-251228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-8) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-code-preview-251028](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-code) |[Chat API](https://www.volcengine.com/docs/82379/1494384?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-1-6-lite-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-lite) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-1-6-vision-250815](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-vision) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-1-6-flash-250828](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-1-6-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-1-6-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[doubao-seed-1-6-flash-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[doubao-1-5-pro-32k-character-250715](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Context API](https://docs.byteplus.com/en/docs/ModelArk/1346559)|\
| | |前缀缓存|\
| | |Session 缓存的rolling_tokens 模式 |
|[doubao-1-5-pro-32k-character-250228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Context API](https://docs.byteplus.com/en/docs/ModelArk/1346559)|\
| | |Session缓存的rolling_tokens 模式 |
|[doubao-1-5-pro-32k-250115](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-pro-32k) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Context API](https://docs.byteplus.com/en/docs/ModelArk/1346559)|\
| | |前缀缓存|\
| | |Session 缓存的rolling_tokens 模式 |
|[doubao-1-5-lite-32k-250115](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-1-5-lite-32k) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Context API](https://docs.byteplus.com/en/docs/ModelArk/1346559)|\
| | |前缀缓存|\
| | |Session 缓存的rolling_tokens 模式 |
|[glm-4-7-251222](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=glm-4-7) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[deepseek-v3-2-251201](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-2) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[deepseek-v3-1-terminus](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-1) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Responses API](https://www.volcengine.com/docs/82379/1569618)|\
| | |前缀缓存|\
| | |Session缓存 |
|[deepseek-v3-250324](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Context API](https://docs.byteplus.com/en/docs/ModelArk/1346559)|\
| | |前缀缓存|\
| | |Session 缓存的rolling_tokens 模式 |
|[deepseek-r1-250528](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-r1) |[Batch API](https://www.volcengine.com/docs/82379/2123267?lang=zh) |[Context API](https://docs.byteplus.com/en/docs/ModelArk/1346559)|\
| | |前缀缓存 |

<span id="86588b72"></span>
# 结构化输出能力(beta)
介绍: [结构化输出(beta)](/docs/82379/1568221) | API: [Responses API](https://www.volcengine.com/docs/82379/1569618)、[Chat API](https://www.volcengine.com/docs/82379/1494384)
<span id="79e2b6f0"></span>
## 推荐模型

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |能力支持 |**长度限制（token）**  |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-2-0-lite-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-lite) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用|最大思维链: 128k | |\
| |结构化输出 | | |
|[doubao-seed-2-0-mini-260215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-2-0-mini) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k): ](https://www.volcengine.com/docs/82379/1399009#0001)128k| |\
| |工具调用|最大思维链: 128k | |\
| |结构化输出 | | |

<span id="b0d6f164"></span>
## 往期模型

<span aceTableMode="list" aceTableWidth="3,2,3,2"></span>
|模型 ID (Model ID) |能力支持 |**长度限制（token）**  |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|
|[doubao-seed-1-8-251228](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-8) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出 | | |
|[doubao-seed-1-6-vision-250815](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-vision) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |GUI 任务处理|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出| | |\
| || | |\
| |* json_schema| | |\
| |* json_object | | |
|[doubao-seed-1-6-flash-250828](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |视觉定位|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |多模态理解|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出| | |\
| || | |\
| |* json_schema| | |\
| |* json_object | | |
|[doubao-seed-1-6-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出| | |\
| || | |\
| |* `json_schema`| | |\
| |* `json_object` | | |
|[doubao-seed-1-6-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |多模态理解|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |工具调用|最大思维链: 32k | |\
| |结构化输出| | |\
| || | |\
| |* `json_schema`| | |\
| |* `json_object` | | |
|[doubao-seed-1-6-flash-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed-1-6-flash) |深度思考|上下文窗口: 256k|最大 RPM: 30000|\
| |文本生成|最大输入: 224k|最大 TPM: 5000000 |\
| |视觉定位|[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| |多模态理解|最大思维链: 32k | |\
| |工具调用| | |\
| |结构化输出| | |\
| || | |\
| |* `json_schema`| | |\
| |* `json_object` | | |
|[deepseek-v3-1-terminus](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-v3-1) |深度思考|上下文窗口: 128k|最大 RPM: 30000|\
| |文本生成|最大输入: 96k|最大 TPM: 5000000 |\
| |工具调用 |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)32k| |\
| | |最大思维链: 32k | |
|[deepseek-r1-250528](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=deepseek-r1) |深度思考|上下文窗口: 128k|最大 RPM: 30000|\
| |工具调用 |最大输入: 96k|最大 TPM: 5000000 |\
| | |[最大回答(默认 4k):](https://www.volcengine.com/docs/82379/1399009#0001)16k| |\
| | |最大思维链: 32k | |

<span id="7571da3f"></span>
# 视频生成能力
教程: [Doubao Seedance 2.0 系列教程](/docs/82379/2291680) | API: [Video Generation API](https://www.volcengine.com/docs/82379/1520758)

<span aceTableMode="list" aceTableWidth="3,2,2,3"></span>
|**模型 ID（Model ID）**  |**模型能力** |**输出视频格式** |限流|\
| | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129)|\
| | | ||\
| | | |**default: 在线推理**|\
| | | |**flex: 离线推理** |
|---|---|---|---|
|[doubao-seedance-2-0-260128](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedance-2-0&projectName=default)`音画同生` |多模态生视频|分辨率:|default:|\
| |编辑视频|480p, 720p, 1080p||\
| |延长视频|帧率: 24 fps|* 最大 RPM:|\
| |图生视频\-首尾帧|时长: 4~15 秒|   * 企业用户: 600|\
| |图生视频\-首帧|视频格式: mp4 |   * 个人用户: 180|\
| |文生视频 | |* 最大并发:|\
| | | |   * 企业用户: 10|\
| | | |   * 个人用户: 3|\
| | | ||\
| | | |flex:|\
| | | ||\
| | | |* 暂不支持 |
|[doubao-seedance-2-0-fast-260128](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedance-2-0-fast)`音画同生` |多模态生视频|分辨率:|default:|\
| |编辑视频|480p, 720p||\
| |延长视频|帧率: 24 fps|* 最大 RPM:|\
| |图生视频\-首尾帧|时长: 4~15 秒|   * 企业用户: 600|\
| |图生视频\-首帧|视频格式: mp4 |   * 个人用户: 180|\
| |文生视频 | |* 最大并发:|\
| | | |   * 企业用户: 10|\
| | | |   * 个人用户: 3|\
| | | ||\
| | | |flex:|\
| | | ||\
| | | |* 暂不支持 |
|[doubao-seedance-1-5-pro-251215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedance-1-5-pro)`音画同生` |图生视频\-首尾帧|分辨率:|default:|\
| |图生视频\-首帧|480p, 720p，1080p||\
| |文生视频 |帧率: 24 fps|* 最大 RPM: 600|\
| | |时长: 4~12 秒|* 最大并发: 10|\
| | |视频格式: mp4 ||\
| | | |flex:|\
| | | ||\
| | | |* 最大 TPD: 5000亿 |
|[doubao-seedance-1-0-pro-250528](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedance-1-0-pro&projectName=default) |图生视频\-首尾帧|分辨率:|default:|\
| |图生视频\-首帧|480p, 720p, 1080p||\
| |文生视频 |帧率: 24 fps|* 最大 RPM: 600|\
| | |时长: 2~12 秒|* 最大并发: 10|\
| | |视频格式: mp4 ||\
| | | |flex:|\
| | | ||\
| | | |* 最大 TPD: 5000亿 |
|[doubao-seedance-1-0-pro-fast-251015](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedance-1-0-pro-fast&projectName=default) |图生视频\-首帧|分辨率:|default:|\
| |文生视频 |480p, 720p, 1080p||\
| | |帧率: 24 fps|* 最大 RPM: 600|\
| | |时长: 2~12 秒|* 最大并发: 10|\
| | |视频格式: mp4 ||\
| | | |flex:|\
| | | ||\
| | | |* 最大 TPD: 5000亿 |
|[doubao-seedance-1-0-lite-t2v-250428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedance-1-0-lite-t2v&projectName=default) |文生视频 |分辨率:|default|\
| | |480p, 720p, 1080p||\
| | |帧率: 24 fps|* 最大 RPM: 300|\
| | |时长: 2~12 秒|* 最大并发: 5|\
| | |视频格式: mp4 ||\
| | | |flex|\
| | | ||\
| | | |* 最大 TPD: 2500亿 |
|[doubao-seedance-1-0-lite-i2v-250428](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedance-1-0-lite-i2v&projectName=default) |图生视频\-参考图|分辨率:|default|\
| |图生视频\-首尾帧|480p, 720p, 1080p||\
| |图生视频\-首帧 |帧率: 24 fps|* 最大 RPM: 300|\
| | |时长: 2~12 秒|* 最大并发: 5|\
| | |视频格式: mp4 ||\
| | | |flex|\
| | | ||\
| | | |* 最大 TPD: 2500亿 |

<span id="9df4d9fd"></span>
# 图片生成能力
教程: [Seedream 4.0-5.0 教程](/docs/82379/1824121) | API: [Image generation API](https://www.volcengine.com/docs/82379/1541523)
<span id="b8c9fd67"></span>
## 推荐模型

<span aceTableMode="list" aceTableWidth="3,2,3"></span>
|模型 ID (Model ID) |**能力支持** |限流|\
| | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129)|\
| | ||\
| | |**最大 IPM**|\
| | |张 / 分钟 |
|---|---|---|
|[doubao-seedream-5-0-260128](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedream-5-0)|文生图|500 |\
|(同时支持：doubao\-seedream\-5\-0\-lite\-260128) |图生图| |\
| || |\
| |* 单张图生图| |\
| |* 多参考图生图| |\
| || |\
| |生成组图| |\
| || |\
| |* 文生组图| |\
| |* 单张图生组图| |\
| |* 多参考图生组图 | |
|[doubao-seedream-4-5-251128](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedream-4-5) |^^|500 |
|[doubao-seedream-4-0-250828](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedream-4-0) |^^|500 |
|[doubao-seedream-3-0-t2i-250415](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seedream-3-0-t2i) |文生图 |500 |

<span id="21b9a17a"></span>
# 3D生成能力
教程: [3D 生成](/docs/82379/1874993) | API: [3D生成 API](https://www.volcengine.com/docs/82379/1856268)

<span aceTableMode="list" aceTableWidth="3,2,2,2,1"></span>
|**模型 ID (Model ID)**  |**能力支持** |**产物规格** |**限流**|**免费额度**|\
| | | |> **非刚性保障，受平台负载/调用方式影响，详见**[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |（token） |
|---|---|---|---|---|
|[doubao-seed3d-2-0-260328](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-seed3d-2-0) |图生3D|产物面数|最大 RPM: 300|200万 |\
| |||最大并发: 5 | |\
| |* 生成带纹理和 PBR 材质的3D文件 |* 100000| | |\
| | |* 500000| | |\
| | |* 1000000| | |\
| | || | |\
| | |产物格式| | |\
| | || | |\
| | |* glb, obj, usd, usdz | | |
|[hyper3d-gen2-260112](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=hyper3d-gen2) |文生3D、图生3D|产物面数|最大 RPM: 60|15万 |\
| |||最大并发: 3 | |\
| |* 白模|* 三角面模型：[500, 1,000,000]| | |\
| |* 带纹理模型|* 四边面模型：[1,000, 200,000]| | |\
| |* PBR 材质模型|| | |\
| |* 带纹理 + PBR 材质模型 |产物格式| | |\
| | || | |\
| | |* glb, obj, stl, fbx, usdz | | |
|[hitem3d-2-0-251223](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=hitem3d-2-0) |图生3D|产物面数|最大 RPM: 600|50万 |\
| |||最大并发: 30 | |\
| |* 标准白模|* [100000, 2000000]| | |\
| |* 标准纹理模型|| | |\
| |* 高精白模|产物格式| | |\
| |* 高精纹理模型 || | |\
| | |* glb, obj, stl, fbx, usdz| | |\
| | || | |\
| | |产物分辨率| | |\
| | || | |\
| | |* 1536、1536 pro | | |

<span id="0ee80bb9"></span>
# 向量化能力
教程: [向量化](/docs/82379/1409291) | API: [Embeddings Multimodal API](https://www.volcengine.com/docs/82379/1523520)

<span aceTableMode="list" aceTableWidth="3,2,1,2,2"></span>
|模型 ID (Model ID) |**能力支持** |**上下文窗口** |**最高向量维度** |限流|\
| | | | |> 非刚性保障，受平台负载/调用方式影响，详见[文档](https://www.volcengine.com/docs/82379/1848593?lang=zh#263ca129) |
|---|---|---|---|---|
|[doubao-embedding-vision-251215](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-embedding-vision) |多模态向量化|128k |2048|最大 RPM: 15000|\
| |> 支持 视频、文本、图片输入 | |> 支持1024降维使用 |最大 TPM: 1200000 |
|[doubao-embedding-vision-250615](https://console.volcengine.com/ark/region:ark+cn-beijing/model/detail?Id=doubao-embedding-vision) |多模态向量化|128k |2048|最大 RPM: 15000|\
| |> 支持 视频、文本、图片输入 | |> 支持1024降维使用 |最大 TPM: 1200000 |

不同模型服务支持的能力及单价各不相同，本文为您介绍各模型的计费公式及单价，方便您进行模型价格查阅和比较。
:::tip

* 如需了解计费方式及详细计费逻辑，请参见 [模型服务计费说明](/docs/82379/1544681)。
* 支持通过 [价格计算器](https://www.volcengine.com/pricing?product=ark_bd&tab=2) **预估** 满足业务需求所需的费用。
* 本文价格和 [定价详情页](https://www.volcengine.com/pricing?product=ark_bd&tab=1) 仅作为商品规格和价格的参考，具体可购买的商品规格及费用请以实际下单结果为准。

:::
<span id="76de5911"></span>
# 大语言模型
<span id="aa1874cf"></span>
## 在线推理（常规）

<span aceTableMode="list" aceTableWidth="3,2,1,1,1,1"></span>
|模型名称 |条件|输入|缓存存储|缓存输入|输出|\
| |千 token |元/百万token |元/百万 token /小时 |元/百万token |元/百万token |
|---|---|---|---|---|---|
|doubao\-seed\-2.0\-pro |输入长度 [0, 32] |3.2 |0.017 |0.64 |16.0 |
|^^|输入长度 (32, 128] |4.8 |0.017 |0.96 |24.0 |
|^^|输入长度 (128, 256] |9.6 |0.017 |1.92 |48.0 |
|doubao\-seed\-2.0\-lite |输入长度 [0, 32] |0.6 |0.017 |0.12 |3.6 |
|^^|输入长度 (32, 128] |0.9 |0.017 |0.18 |5.4 |
|^^|输入长度 (128, 256] |1.8 |0.017 |0.36 |10.8 |
|doubao\-seed\-2.0\-mini |输入长度 [0, 32] |0.2 |0.017 |0.04 |2.0 |
|^^|输入长度 (32, 128] |0.4 |0.017 |0.08 |4.0 |
|^^|输入长度 (128, 256] |0.8 |0.017 |0.16 |8.0 |
|doubao\-seed\-2.0\-code |输入长度 [0, 32] |3.2 |0.017 |0.64 |16.0 |
|^^|输入长度 (32, 128] |4.8 |0.017 |0.96 |24.0 |
|^^|输入长度 (128, 256] |9.6 |0.017 |1.92 |48.0 |
|doubao\-seed\-1.8 |输入长度 [0, 32]|0.80 |0.017 |0.16 |2.00 |\
| |且输出长度 [0, 0.2] | | | | |
|^^|输入长度 [0, 32]|0.80 |0.017 |0.16 |8.00 |\
| |且输出长度 (0.2,+∞) | | | | |
|^^|输入长度 (32, 128] |1.20 |0.017 |0.16 |16.00 |
|^^|输入长度 (128, 256] |2.40 |0.017 |0.16 |24.00 |
|doubao\-seed\-character |输入长度 [0, 32] |0.80 |0.017 |0.16 |2.00 |
|^^|输入长度 (32, 128] |1.20 |0.017 |0.16 |6.00 |
|doubao\-seed\-code |输入长度 [0, 32] |1.20 |0.017 |0.24 |8.00 |
|^^|输入长度 (32, 128] |1.40 |0.017 |0.24 |12.00 |
|^^|输入长度 (128, 256] |2.80 |0.017 |0.24 |16.00 |
|doubao\-seed\-1.6 |输入长度 [0, 32]|0.80 |0.017 |0.16 |2.00 |\
| |且输出长度 [0, 0.2] | | | | |
|^^|输入长度 [0, 32]|0.80 |0.017 |0.16 |8.00 |\
| |且输出长度 (0.2,+∞) | | | | |
|^^|输入长度 (32, 128] |1.20 |0.017 |0.16 |16.00 |
|^^|输入长度 (128, 256] |2.40 |0.017 |0.16 |24.00 |
|doubao\-seed\-1.6\-lite |输入长度 [0, 32]|0.30 |0.017 |0.06 |0.60 |\
| |且输出长度 [0, 0.2] | | | | |
|^^|输入长度 [0, 32]|0.30 |0.017 |0.06 |2.40 |\
| |且输出长度 (0.2,+∞) | | | | |
|^^|输入长度 (32, 128] |0.60 |0.017 |0.06 |4.00 |
|^^|输入长度 (128, 256] |1.20 |0.017 |0.06 |12.00 |
|doubao\-seed\-1.6\-flash |输入长度 [0, 32] |0.15 |0.017 |0.03 |1.50 |
|^^|输入长度 (32, 128] |0.30 |0.017 |0.03 |3.00 |
|^^|输入长度 (128, 256] |0.60 |0.017 |0.03 |6.00 |
|doubao\-seed\-1.6\-vision |输入长度 [0, 32] |0.80 |0.017 |0.16 |8.00 |
|^^|输入长度 (32, 128] |1.20 |0.017 |0.16 |16.00 |
|^^|输入长度 (128, 256] |2.40 |0.017 |0.16 |24.00 |
|doubao\-seed\-translation |\- |1.20 |不支持 |不支持 |3.60 |
|doubao\-1.5\-pro\-32k |\- |0.80 |0.017 |0.16 |2.00 |
|doubao\-1.5\-lite\-32k |\- |0.30 |0.017 |0.06 |0.60 |
|doubao\-1.5\-vision\-pro |\- |3.00 |不支持 |不支持 |9.00 |
|glm\-4.7 |输入长度 [0, 32]|2.0 |0.017 |0.4 |8.0 |\
| |且输出长度 [0, 0.2] | | | | |
|^^|输入长度 [0, 32]|3.0 |0.017 |0.6 |14.0 |\
| |且输出长度 (0.2,+∞) | | | | |
|^^|输入长度 (32, 200] |4.0 |0.017 |0.8 |16.0 |
|deepseek\-v3.2 |输入长度 [0, 32] |2.00 |0.017 |0.4 |3.00 |
|^^|输入长度 (32, 128] |4.00 |0.017 |0.4 |6.00 |
|deepseek\-v3.1 |\- |4.00 |0.017 |0.80 |12.00 |
|deepseek\-v3 |\- |2.00 |0.017 |0.40 |8.00 |
|deepseek\-r1 |\- |4.00 |0.017 |0.80 |16.00 |


> * 按 token 后付费，计算公式：
    >    * `在线推理费用 = 输入单价 × 输入token + 缓存输入单价 × 缓存命中token + 缓存存储单价 × 缓存存储token × 时长 + 输出单价 × 输出token`
> * 分段计费：部分模型适用，不同的输入长度（和输出长度），token单价不同：
    >    * 举例：请求输入 200k tokens，输出 14k tokens，满足 **输入长度 (128, 256]**  条件，模型输入输出 token 按照：输入 2.4 元/百万 token，输出 24 元/百万 token 单价计费。
> * 常见问题： [如何查看历史调用的输入输出长度的区间分布？](/docs/82379/1359411#fba666f2)

<span id="16f7c300"></span>
## 在线推理（常规/音频理解）

<span aceTableMode="list" aceTableWidth="3,2,1,1"></span>
|模型名称 |条件|音频输入|音频缓存命中|\
| |千 token |元/百万token |元/百万token |
|---|---|---|---|
|doubao\-seed\-2.0\-lite |输入长度 [0, 32] |9.0 元 |1.8 元 |
|^^|输入长度 (32, 128] |13.5 元 |2.7 元 |
|^^|输入长度 (128, 256] |27.0 元 |5.4 元 |
|doubao\-seed\-2.0\-mini |输入长度 [0, 32] |3.0 元 |0.6 元 |
|^^|输入长度 (32, 128] |6.0 元 |1.2 元 |
|^^|输入长度 (128, 256] |12.0 元 |2.4 元 |

> 支持音频理解的模型，请参见[音频理解能力](/docs/82379/1330310#9619c0ba)。

<span id="d3774bbd"></span>
## 在线推理（低延迟）

<span aceTableMode="list" aceTableWidth="3,2,1,1,1"></span>
|模型名称 |条件|输入|缓存输入|输出|\
| |千 token |元/百万token |元/百万token |元/百万token |
|---|---|---|---|---|
|doubao\-seed\-2.0\-pro |输入长度 [0, 32] |9.6 |1.92 |48.0 |
|^^|输入长度 (32, 128] |14.4 |2.88 |72.0 |
|^^|输入长度 (128, 256] |28.8 |5.76 |144.0 |
|doubao\-seed\-2.0\-lite |输入长度 [0, 32] |1.2 |0.24 |7.2 |
|^^|输入长度 (32, 128] |1.8 |0.36 |10.8 |
|^^|输入长度 (128, 256] |3.6 |0.72 |21.6 |

<span id="952683a2"></span>
## 在线推理（TPM 保障包）

<span aceTableMode="list" aceTableWidth="3,2,2,2"></span>
|模型 |计费方式 |输入|输出|\
| | |元/每10K TPM |元/每1K TPM |
|---|---|---|---|
|doubao\-seed\-1.8 |按购买时长后付费 |1.920 |0.480 |
|^^|包天预付费 |23.040 |5.760 |
|doubao\-seed\-1.6 |按购买时长后付费 |1.920 |0.480 |
|^^|包天预付费 |23.040 |5.760 |
|doubao\-seed\-1.6\-vision |按购买时长后付费 |1.920 |0.480 |
|^^|包天预付费 |23.040 |5.760 |
|doubao\-seed\-1.6\-flash|按购买时长后付费 |0.360 |0.360 |\
|> 0615版本不支持 | | | |
|^^|包天预付费 |4.320 |4.320 |
|doubao\-1.5\-vision\-pro |按购买时长后付费 |7.200 |2.160 |
|^^|包天预付费 |86.400 |25.920 |
|doubao\-1.5\-pro\-32k|按购买时长后付费 |1.920 |0.480 |\
|> 包含 character\-250715 版本 | | | |
|^^|包天预付费 |23.040 |5.760 |
|doubao\-1.5\-lite\-32k |按购买时长后付费 |0.72 |0.144 |
|^^|包天预付费 |8.64 |1.728 |
|doubao\-pro\-32k |按购买时长后付费 |1.920 |0.480 |
|^^|包天预付费 |23.040 |5.760 |
|deepseek\-v3.2 |按购买时长后付费 |7.2 |1.08 |
|^^|包天预付费 |86.4 |12.96 |
|deepseek\-v3.1 |按购买时长后付费 |9.60 |2.88 |
|^^|包天预付费 |115.20 |34.56 |
|deepseek\-v3 |按购买时长后付费 |4.80 |1.92 |
|^^|包天预付费 |57.60 |23.04 |
|deepseek\-r1 |按购买时长后付费 |9.60 |3.84 |
|^^|包天预付费 |115.20 |46.08 |


> * 相比普通的按token计费模式，TPM保障包具备更高并发，更低的延迟，更强稳定性。支持的模型，以[接入点创建页](https://console.volcengine.com/ark/region:ark+cn-beijing/endpoint/create)可选的付费方式为准。
> * 支持「按购买时长后付费」和「包天预付费」两种方式叠加购买，可灵活组合。
> * **doubao\-seed\-1.6 系列及之后模型，deepseek\-v3.2 模型，不同长度请求抵扣 TPM 速度不同**，可通过 TPM 计算器查看相应的抵扣系数，估算实际需购买的**可抵扣TPM**。

<span id="a6471f38"></span>
## 批量推理

<span aceTableMode="list" aceTableWidth="3,2,1,1,2"></span>
|模型名称 |条件|输入|缓存命中|输出|\
| |千 token |元/百万token |元/百万token |元/百万token |
|---|---|---|---|---|
|doubao\-seed\-2.0\-pro |输入长度 [0, 32] |1.6 |0.64 |8.0 |
|^^|输入长度 (32, 128] |2.4 |0.96 |12.0 |
|^^|输入长度 (128, 256] |4.8 |1.92 |24.0 |
|doubao\-seed\-2.0\-lite |输入长度 [0, 32] |0.3 |0.12 |1.8 |
|^^|输入长度 (32, 128] |0.45 |0.18 |2.7 |
|^^|输入长度 (128, 256] |0.9 |0.36 |5.4 |
|doubao\-seed\-2.0\-mini |输入长度 [0, 32] |0.1 |0.04 |1.0 |
|^^|输入长度 (32, 128] |0.2 |0.08 |2.0 |
|^^|输入长度 (128, 256] |0.4 |0.16 |4.0 |
|doubao\-seed\-2.0\-code |输入长度 [0, 32] |1.6 |0.64 |8.0 |
|^^|输入长度 (32, 128] |2.4 |0.96 |12.0 |
|^^|输入长度 (128, 256] |4.8 |1.92 |24.0 |
|doubao\-seed\-1.8 |输入长度 [0, 32]|0.40 |0.16 |1.00 |\
| |且输出长度 [0, 0.2] | | | |
|^^|输入长度 [0, 32]|0.40 |0.16 |4.00 |\
| |且输出长度 (0.2,+∞) | | | |
|^^|输入长度 (32, 128] |0.60 |0.16 |8.00 |
|^^|输入长度 (128, 256] |1.20 |0.16 |12.00 |
|doubao\-seed\-1.6\-vision |输入长度 [0, 32] |0.40 |0.16 |4.00 |
|^^|输入长度 (32, 128] |0.60 |0.16 |8.00 |
|^^|输入长度 (128, 256] |1.20 |0.16 |12.00 |
|doubao\-seed\-1.6\-lite |输入长度 [0, 32]|0.15 |0.06 |0.30 |\
| |且输出长度 [0, 0.2] | | | |
|^^|输入长度 [0, 32]|0.15 |0.06 |1.20 |\
| |且输出长度 (0.2,+∞) | | | |
|^^|输入长度 (32, 128] |0.30 |0.06 |2.00 |
|^^|输入长度 (128, 256] |0.60 |0.06 |6.00 |
|doubao\-seed\-1.6 |输入长度 [0, 32]|0.40 |0.16 |1.00 |\
| |且输出长度 [0, 0.2] | | | |
|^^|输入长度 [0, 32]|0.40 |0.16 |4.00 |\
| |且输出长度 (0.2,+∞) | | | |
|^^|输入长度 (32, 128] |0.60 |0.16 |8.00 |
|^^|输入长度 (128, 256] |1.20 |0.16 |12.00 |
|doubao\-seed\-1.6\-flash |输入长度 [0, 32] |0.075 |0.03 |0.75 |
|^^|输入长度 (32, 128] |0.150 |0.03 |1.50 |
|^^|输入长度 (128, 256] |0.300 |0.03 |3.00 |
|doubao\-seed\-translation |\- |0.60 |0.24 |1.80 |
|doubao\-1.5\-pro\-32k |\- |0.40 |0.16 |1.00 |
|doubao\-1.5\-lite\-32k |\- |0.15 |0.06 |0.30 |
|doubao\-pro\-32k |\- |0.80 |0.16 |2.00 |
|deepseek\-v3.2 |输入长度 [0, 32] |1.00 |0.40 |1.50 |
|^^|输入长度 (32, 128] |2.00 |0.40 |3.00 |
|deepseek\-v3.1 |\- |2.00 |0.80 |6.00 |
|deepseek\-v3 |\- |1.00 |0.40 |4.00 |
|deepseek\-r1 |\- |2.00 |0.80 |8.00 |


> * 按 token 后付费，计算公式：`批量推理费用 = 输入单价 × 输入token + 缓存命中单价 × 缓存命中token + 输出单价 × 输出token`
> * 部分模型已支持透明前缀缓存能力，无需任何配置，享受命中缓存后的更低单价。
> * doubao\-seed\-1.6 系列支持分段计费，即根据每次请求的输入及输出长度，采用不同 token 单价。
    >    * 举例：当某次请求的输入长度为 200k，输出长度为 14k 时，满足 **输入长度 (128, 256]**  条件，模型产生的所有 token 按照：输入2.4 元/百万 token，输出 24 元/百万 token 单价计费。
> * 查看往期调用的输入输出长度分布，请查看常见问题 [如何查看历史调用的输入输出长度的区间分布？](/docs/82379/1359411#fba666f2)

<span id="02affcb8"></span>
# 视频生成模型
<span id="2864f00a"></span>
## 按token单价

<span aceTableMode="list" aceTableWidth="3,3,3"></span>
|模型 |在线推理|离线推理|\
| |元/百万token |元/百万token |
|---|---|---|
|doubao\-seedance\-2.0|* 输出视频分辨率为 480p，720p|暂不支持 |\
|> 按输出视频分辨率和输入是否包含视频区分定价 |   * 输入不含视频：46.00| |\
| |   * 输入包含视频：28.00| |\
| |* 输出视频分辨率为 1080p| |\
| |   * 输入不含视频：51.00| |\
| |   * 输入包含视频：31.00 | |
|doubao\-seedance\-2.0\-fast|* 输入不含视频：37.00|暂不支持 |\
|> 按输入是否包含视频区分定价|* 输入包含视频：22.00 | |\
|> 不支持输出 1080p 视频 | | |
|doubao\-seedance\-1.5\-pro|* 有声视频：16.00|* 有声视频：8.00|\
|> 按输出视频是否包含声音区分定价 |* 无声视频：8.00 |* 无声视频：4.00 |
|doubao\-seedance\-1.0\-pro |15.00 |7.50 |
|doubao\-seedance\-1.0\-pro\-fast |4.20 |2.10 |
|doubao\-seedance\-1.0\-lite |10.00 |5.00 |


> * 仅对成功生成的视频计费。因审核等原因导致生成失败的，不收取费用。
> * 视频价格估算公式：`按 token 单价 × token 用量`
> * token 用量估算公式：`(输入视频时长+输出视频时长) × 输出视频的宽 × 输出视频的高 × 输出视频的帧率/1024`
    >    * 部分模型支持开启样片模式（生成低画质 Draft 视频，用于快速验证效果），该模式下 token 用量更低，计算公式为`token 用量估算公式结果 × 折算系数`。折算系数与模型有关，Seedance 1.5 pro 的 token 折算系数：无声 0.7；有声 0.6，其他模型暂不支持。
>    * 上述 token 用量均为估算值，准确 token 用量以调用 API 后返回的 usage.**completion_tokens** 字段为准。

<span id="2653dbb3"></span>
## 价格示例
基于 token 用量公式估算的视频单价，方便您直观了解不同规格的视频成本。更多价格示例请参见[火山方舟视频生成模型价格快查表](https://bytedance.larkoffice.com/wiki/FXaYwxzJ5i5Zdik32ipcWzt7nxd?table=tblns3WjGMNbR8sL&view=vewPa39Do4#CategoryScheduledTask)。
<span id="83af2aad"></span>
### doubao\-seedance\-2.0 & 2.0 fast
使用 [Seedance 2.0 系列价格计算器](https://bytedance.larkoffice.com/share/base/form/shrcnP1Bl0mqCP9OHCbjpe1oBkf) 获取更多预估价格

> * 视频价格估算公式：`按 token 单价 × token 用量`=`按 token 单价 × (输入视频时长+输出视频时长) × 输出视频的宽 × 输出视频的高 × 输出视频的帧率/1024`
> * 注意：输入包含视频时， Seedance 2.0 和 Seedance 2.0 fast 模型存在最低 token 用量限制：
    >    * 如果估算 token 用量 ＜ 最低 token 用量，则按最低 token 用量计算视频价格。
>    * 最低 token 用量与分辨率、宽高比、视频输出时长有关，可查询 [最低 token 用量表格（估算值）](https://bytedance.larkoffice.com/wiki/FXaYwxzJ5i5Zdik32ipcWzt7nxd?table=tblmNCuMjADrXtDf&view=vewPa39Do4#CategoryScheduledTask) 或使用 [Seedance 2.0 系列价格计算器](https://bytedance.larkoffice.com/share/base/form/shrcnP1Bl0mqCP9OHCbjpe1oBkf) 估算您的视频任务的最低 token 用量，准确用量以调用 API 后返回的 usage.**completion_tokens** 字段为准。
* **输入不含视频**


<span aceTableMode="list" aceTableWidth="2,2,3,4,4"></span>
|分辨率 |宽高比 |输出视频时长（秒） |doubao\-seedance\-2.0|doubao\-seedance\-2.0\-fast|\
| | | |视频价格（元/个） |视频价格（元/个） |
|---|---|---|---|---|
|480p |16:9 |5 |2.31 |1.86 |
|720p |16:9 |5 |4.97 |4.00 |
|1080p |16:9 |5 |12.39 |不支持 |


* **输入包含视频**


<span aceTableMode="list" aceTableWidth="2,2,3,3,4,4"></span>
|分辨率 |宽高比 |输入视频时长（秒） |输出视频时长（秒） |doubao\-seedance\-2.0|doubao\-seedance\-2.0\-fast|\
| | | | |视频价格（元/个） |视频价格（元/个） |
|---|---|---|---|---|---|
|480p |16:9 |2~15 |5 |2.53~5.62|1.99~4.42|\
| | | | |> 最低价对应输入2~4秒|> 最低价对应输入2~4秒|\
| | | | |> 最高价对应输入15秒 |> 最高价对应输入15秒 |
|720p |16:9 |2~15 |5 |5.44~12.10|4.28~9.50|\
| | | | |> 最低价对应输入2~4秒|> 最低价对应输入2~4秒|\
| | | | |> 最高价对应输入15秒 |> 最高价对应输入15秒 |
|1080p |16:9 |2～15 |5 |13.56~30.13|不支持 |\
| | | | |> 最低价对应输入2~4秒| |\
| | | | |> 最高价对应输入15秒 | |

<span id="dd571290"></span>
### doubao\-seedance\-1.5\-pro

<span aceTableMode="list" aceTableWidth="2,2,2,3,3,3,3"></span>
|分辨率 |宽高比 |时长（秒） |有声视频|Draft 有声|无声视频|Draft无声|\
| | | |价格|视频价格|价格|视频价格|\
| | | |（元/个） |（元/个） |（元/个） |（元/个） |
|---|---|---|---|---|---|---|
|480p |16:9 |5 |0.80 |0.48 |0.40 |0.28 |
|720p |16:9 |5 |1.73 |不支持 |0.86 |不支持 |
|1080p |16:9 |5 |3.89 |不支持 |1.94 |不支持 |

<span id="457edfd0"></span>
# 图片生成模型

<span aceTableMode="list" aceTableWidth="3,6"></span>
|模型名称 |单价|\
| |元/张 |
|---|---|
|doubao\-seedream\-5.0\-lite |0.22 |
|doubao\-seedream\-4.5 |0.25 |
|doubao\-seedream\-4.0 |0.2 |
|doubao\-seedream\-3.0\-t2i |0.259 |


> * 按成功输出图片数量计费：
    >    * 组图场景按实际生成的图片数量计费。
>    * 因审核等原因未成功输出的图片不计费。

<span id="59e650ae"></span>
# 3D生成模型

<span aceTableMode="list" aceTableWidth="3,3,3"></span>
|模型 |产物（3D 文件） |输出单价|\
| | |元/次 |
|---|---|---|
|doubao\-seed3d\-2.0 |带纹理和 PBR 材质的 3D 模型文件 |2.40|\
| | |> `3.00 万 token / 次` \* `0.80 元/万 token` |
|Hyper3d\-Gen2 |* 白模|1.80|\
| |* 带纹理模型|> `3.00 万 token / 次` \* `0.60 元/万 token` |\
| |* PBR 材质模型| |\
| |* 带纹理 + PBR 材质模型 | |
|Hitem3d\-2.0 |标准白模 |5.80|\
| | |> `7.25 万 token / 次` \* `0.80 元/万 token` |
|^^|标准纹理模型 |10.15|\
| | |> `12.6875 万 token / 次` \* `0.80 元/万 token` |
|^^|高精白模 |8.70|\
| | |> `10.875 万 token / 次` \* `0.80 元/万 token` |
|^^|高精纹理模型 |13.05|\
| | |> `16.3125 万 token / 次` \* `0.80 元/万 token` |


> * 按成功输出 3D 文件数计费。
> * Hyper3d\-Gen2 模型提供优惠资源包，购买链接：[影眸大模型服务资源包](https://console.volcengine.com/common-buy/ark_ym_sanfang%7C%7C7291580783171506476)
    >    * 300万 token：150元，可生成 100次，平均 1.5 元/次。
>    * 3000万 token：1000元，可生成 1000次，平均 1 元/次。

<span id="e68ea83c"></span>
# 向量模型

<span aceTableMode="list" aceTableWidth="3,3,3"></span>
|模型 |文本输入|图片输入|\
| |元/百万 token |元/百万 token |
|---|---|---|
|doubao\-embedding\-vision |0.70 |1.80 |

> 按输入的 tokens 计费：
> 费用 = `文本输入 tokens × 文本输入单价 + 图片输入 tokens × 图片输入单价`
> = `文本输入 tokens × 文本输入单价+ min((width × height)/784，1312 ) × 图片输入单价`

<span id="b3a42676"></span>
# 模型精调
<span id="7e451788"></span>
## 精调\-按 token 后付费

<span aceTableMode="list" aceTableWidth="3,3,3"></span>
|基础模型 ID |LoRA精调|全量精调|\
| |元/百万token |元/百万token |
|---|---|---|
|doubao\-seed\-1.6 |40 |80 |
|doubao\-seed\-1.6\-flash |7 |14 |
|doubao\-1\-5\-pro\-32k\-250115 |50 |100 |
|doubao\-1\-5\-lite\-32k\-250115 |30 |60 |

> 训练费用 = 总 token 数 x 精调单价 =（用户训练集token数+混入token数+验证集token数）x 迭代轮次 x 精调token单价
> * 若 token 数小于 1000，将会上取整为 1000 tokens 计算。

<span id="b2811e92"></span>
## 精调\-按算力付费

<span aceTableMode="list" aceTableWidth="3,3,3"></span>
|算力规格 |计费方式 |定价|\
| | |元/小时 |
|---|---|---|
|方舟A型模型单元 |按量后付费 |25 |
|方舟B型模型单元 |按量后付费 |15 |
|方舟C型模型单元 |按量后付费 |10 |
|方舟D型模型单元 |按量后付费 |20 |

> 训练费用=训练计费时长*使用的模版单价=训练计费时长*模型单元数\*模型单元单价。

<span id="c6d128f7"></span>
## 推理\-在线推理

<span aceTableMode="list" aceTableWidth="3,2,2,2"></span>
|精调模型对应的基础模型 |条件（千 token） |输入|输出|\
| | |元/百万token |元/百万token |
|---|---|---|---|
|doubao\-seed\-1.6 |输入长度 [0, 32] |1.60 |16.00 |
|^^|输入长度 (32, 128] |2.40 |32.00 |
|doubao\-seed\-1.6\-flash |输入长度 [0, 32] |0.30 |3.00 |
|^^|输入长度 (32, 128] |0.60 |6.00 |
|doubao\-1.5\-pro\-32k |\- |2.00 |5.00 |
|doubao\-1.5\-lite\-32k |\- |0.75 |1.50 |
|doubao\-pro\-32k |\- |0.80 |2.00 |

> 按 token 后付费价格，仅部分 doubao 模型在精调后支持按 token 付费，以[接入点创建页](https://console.volcengine.com/ark/region:ark+cn-beijing/endpoint/create)可选的付费方式为准。

<span id="0c211d41"></span>
## 推理\-批量推理

<span aceTableMode="list" aceTableWidth="3,2,1,1,2"></span>
|精调模型对应的基础模型 |条件（千 token） |输入|缓存命中|输出|\
| | |元/百万token |元/百万token |元/百万token |
|---|---|---|---|---|
|doubao\-seed\-1.6 |输入长度 [0, 32] |0.40 |0.16 |4.00 |
|^^|输入长度 (32, 128] |0.60 |0.16 |8.00 |
|^^|输入长度 (128, 256] |1.20 |0.16 |12.00 |
|doubao\-seed\-1.6\-flash |输入长度 [0, 32] |0.075 |0.03 |0.75 |
|^^|输入长度 (32, 128] |0.15 |0.03 |1.50 |
|^^|输入长度 (128, 256] |0.30 |0.03 |3.00 |
|doubao\-1.5\-pro\-32k |\- |0.40 |0.16 |1.00 |
|doubao\-1.5\-lite\-32k |\- |0.15 |0.06 |0.30 |
|doubao\-pro\-32k |\- |0.80 |0.16 |2.00 |

> 按token后付费，相比在线推理，价格低至50%。

<span id="c26435c9"></span>
# 模型单元

<span aceTableMode="list" aceTableWidth="3,3,3"></span>
|机型 |计费方式 |定价|\
| | |元/个 |
|---|---|---|
|方舟A型模型单元 |按购买时长后付费 |25.00 |
|^^|包月预付费 |16700.00 |
|方舟B型模型单元 |按购买时长后付费 |15.00 |
|^^|包月预付费 |10400.00 |
|方舟C型模型单元 |按购买时长后付费 |10.00 |
|^^|包月预付费 |7100.00 |
|方舟D型模型单元 |按购买时长后付费 |20.00 |
|^^|包月预付费 |12800.00 |

> 支持「按购买时长后付费」和「包月预付费」两种方式叠加购买，可灵活组合。
> **提供** [单元计算器](https://console.volcengine.com/ark/region:ark+cn-beijing/endpoint/create) 估算需要的机型数量。更推荐通过实际业务流量压测，计算需要的机型和数量。

<span id="3adb5876"></span>
# 工具及插件
<span id="f2e7c4f6"></span>
## 联网内容插件

<span aceTableMode="list" aceTableWidth="3,2,4"></span>
|服务项 |价格|说明 |\
| |元/千次 | |
|---|---|---|
|联网资源 |4 |实时搜索互联网公开域内容，每月提供2万次免费额度。 |
|头条资源 |6 |实时搜索今日头条图文内容，并提供内容详情信息供展示交互卡片。 |
|抖音资源 |6 |实时搜索抖音百科内容，并提供内容详情信息供展示交互卡片。 |
|墨迹天气 |6 |实时搜索墨迹天气内容资源。 |


> * 出账及计费：按量后付费
> * 用量：每次请求产生的调用次数，可返回结构体的 **source_type** 字段计算得到。
> * 更多说明请参见 [联网内容插件功能说明](/docs/82379/1338552)。

<span id="abf4f1e8"></span>
## 豆包助手

<span aceTableMode="list" aceTableWidth="3,2,4"></span>
|服务项 |价格|说明 |\
| |元/次 | |
|---|---|---|
|日常沟通 |0.1 |全能助手，自然交流，多轮对话，高情商人格化聊天。 |
|深度沟通 |0.2 |深度理解，精准解析，先思考再回答，复杂问题尽在掌握。 |
|联网搜索 |0.2 |全网搜索，信源丰富，无需费力找资料，一键搜索实时资讯。 |
|边想边搜 |0.5 |逻辑缜密，深度洞察，遇难题问豆包，想得更深，答得更准。 |


> * 出账及计费：按量后付费
> * 用量：每次请求产生的调用次数，可返回结构体的 **source_type** 字段计算得到。
> * 更多说明请参见 [联网内容插件功能说明](/docs/82379/1338552)。

<span id="bce8c602"></span>
## 知识库

<span aceTableMode="list" aceTableWidth="6,3"></span>
|服务项 |价格 |
|---|---|
|计算资源\-知识库【旗舰版】 |0.45 元/CU/小时 |
|离线存储资源\-知识库【旗舰版】 |0.0015 元/GB/小时 |
|标准计算资源\-知识库【标准版】 |0.0416 元/知识库/小时 |
|文本向量模型\-知识库【通用】 |0.0005 元/千token |
|文本向量模型（多功能版）\-知识库【通用】 |0.0005 元/千token |
|文本向量模型（Doubao\-embedding）\-知识库【通用】 |0.0005 元/千token |
|文本向量模型（Doubao\- embedding\-large）\-知识库【通用】 |0.0007 元/千token |
|多模态向量模型（Doubao\-embedding\-vision\-text）\-知识库【通用】 |0.0007 元/千token |
|多模态向量模型（Doubao\-embedding\-vision\-image）\-知识库【通用】 |0.0018 元/千token |
|重排模型\-知识库【通用】 |0.0005 元/千token |

> 更多说明请参见 [知识库计费](/docs/82379/1263336)。

<span id="f47e6c9b"></span>
# Coding Plan 个人版

<span aceTableMode="list" aceTableWidth="3,3,3"></span>
|套餐类型 |订阅时长 |价格 |
|---|---|---|
|Lite 套餐 |1 个月 |40 元/月 |
|^^|3 个月 |120 元/季 |
|Pro 套餐 |1 个月 |200 元/月 |
|^^|3 个月 |600 元/季 |

> 套餐信息及特惠活动参见[套餐概览](/docs/82379/1925114)。



根据输入的提示词生成连贯清晰的文本内容。
:::tip
方舟平台的新用户？获取 API Key 及 开通模型等准备工作，请参见 [快速入门](/docs/82379/1399008)。
:::
<span id="18cf565a"></span>
# 快速开始

<span aceTableMode="list" aceTableWidth="3,6"></span>
|输入 |输出预览 |
|---|---|
|```Plain|```Plain|\
|请将下面内容进行结构化处理：火山方舟是火山引擎推出的大模型服务平台，提供模型训练、推理、评测、精调等全方位功能与服务，并重点支撑大模型生态。 火山方舟通过稳定可靠的安全互信方案，保障模型提供方的模型安全与模型使用者的信息安全，加速大模型能力渗透到千行百业，助力模型提供方和使用者实现商业新增长。|# 火山方舟大模型服务平台结构化信息|\
|```||\
| |## 一、基本属性|\
| |1. **推出主体**：火山引擎|\
| |2. **平台定位**：大模型服务平台|\
| ||\
| |## 二、核心功能与服务|\
| |提供模型训练、推理、评测、精调等全方位大模型相关功能与服务，核心定位包括：|\
| |1.  支撑大模型生态建设|\
| |2.  推动大模型能力向千行百业渗透|\
| ||\
| |## 三、安全保障方案|\
| |通过稳定可靠的安全互信方案，双向保障信息安全：|\
| |1.  模型提供方：保障其模型安全|\
| |2.  模型使用者：保障其信息安全|\
| ||\
| |## 四、价值目标|\
| |助力模型提供方和使用者实现商业新增长，构建大模型生态下的商业共赢模式。|\
| |```|\
| | |


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="pXgR4C1HbE"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $ARK_API_KEY" \\
  -d '\{
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        \{"role": "user", "content": "请将下面内容进行结构化处理：火山方舟是火山引擎推出的大模型服务平台，提供模型训练、推理、评测、精调等全方位功能与服务，并重点支撑大模型生态。 火山方舟通过稳定可靠的安全互信方案，保障模型提供方的模型安全与模型使用者的信息安全，加速大模型能力渗透到千行百业，助力模型提供方和使用者实现商业新增长。"\}
    ],
     "thinking":\{
         "type":"disabled"
     \}
  \}'
\`\`\`


* 按需替换 Model ID，查询 Model ID 请参见 [模型列表](/docs/82379/1330310)。
`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="TIOtn8he6H"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

# 初始化Ark客户端
client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        \{"role": "user", "content": "请将下面内容进行结构化处理：火山方舟是火山引擎推出的大模型服务平台，提供模型训练、推理、评测、精调等全方位功能与服务，并重点支撑大模型生态。 火山方舟通过稳定可靠的安全互信方案，保障模型提供方的模型安全与模型使用者的信息安全，加速大模型能力渗透到千行百业，助力模型提供方和使用者实现商业新增长。"\},
    ],
    # thinking=\{"type": "disabled"\}, #  Manually disable deep thinking
)
print(completion.choices[0].message.content)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="hQ2rYRPQu0"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() \{
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    
    ctx := context.Background()
    req := model.CreateChatCompletionRequest\{
        // Replace with Model ID
       Model: "doubao-seed-2-0-lite-260215",
       Messages: []*model.ChatCompletionMessage\{
          \{
             Role: model.ChatMessageRoleUser,
             Content: &model.ChatCompletionMessageContent\{
                StringValue: volcengine.String("请将下面内容进行结构化处理：火山方舟是火山引擎推出的大模型服务平台，提供模型训练、推理、评测、精调等全方位功能与服务，并重点支撑大模型生态。 火山方舟通过稳定可靠的安全互信方案，保障模型提供方的模型安全与模型使用者的信息安全，加速大模型能力渗透到千行百业，助力模型提供方和使用者实现商业新增长。"),
             \},
          \},
       \},
       Thinking: &model.Thinking\{
            Type: model.ThinkingTypeDisabled, // Manually disable deep thinking
            // Type: model.ThinkingTypeEnabled, // Manually enable deep thinking
        \},
    \}

    resp, err := client.CreateChatCompletion(ctx, req)
    if err != nil \{
       fmt.Printf("standard chat error: %v\\n", err)
       return
    \}
    fmt.Println(*resp.Choices[0].Message.Content.StringValue)
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="nZwnMhwudi"><RenderMd content={`\`\`\`java
package com.ark.sample;

import com.volcengine.ark.runtime.model.completion.chat.*;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.ArrayList;
import java.util.List;

public class ChatCompletionsExample \{
    public static void main(String[] args) \{
        String apiKey = System.getenv("ARK_API_KEY");
        // The base URL for model invocation
        ArkService service = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();
        final List<ChatMessage> messages = new ArrayList<>();
        final ChatMessage userMessage = ChatMessage.builder().role(ChatMessageRole.USER).content("请将下面内容进行结构化处理：火山方舟是火山引擎推出的大模型服务平台，提供模型训练、推理、评测、精调等全方位功能与服务，并重点支撑大模型生态。 火山方舟通过稳定可靠的安全互信方案，保障模型提供方的模型安全与模型使用者的信息安全，加速大模型能力渗透到千行百业，助力模型提供方和使用者实现商业新增长。").build();
        messages.add(userMessage);

        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
               .model("doubao-seed-2-0-lite-260215")//Replace with Model ID
               .messages(messages)
               // .thinking(new ChatCompletionRequest.ChatCompletionRequestThinking("disabled")) // Manually disable deep thinking
               .build();
        service.createChatCompletion(chatCompletionRequest).getChoices().forEach(choice -> System.out.println(choice.getMessage().getContent()));
        // shutdown service
        service.shutdownExecutor();
    \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

:::tip
使用 Responses API 实现单轮对话的示例，请参见[快速开始](/docs/82379/1958520#17377051)。
:::
<span id="3e5edc90"></span>
# 模型与API
支持的模型：[文本生成能力](/docs/82379/1330310#b318deb2)
支持的API ：

* [Responses API](https://www.volcengine.com/docs/82379/1569618)：新推出的 API，简洁上下文管理，增强工具调用能力，缓存能力降低成本，新业务及用户推荐。
* [Chat API](https://www.volcengine.com/docs/82379/1494384)：使用广泛的 API，存量业务迁移成本低。

<span id="1d866118"></span>
# 使用示例
<span id="f6222fec"></span>
## 多轮对话
实现多轮对话，需将包含系统消息、模型消息和用户消息的对话历史组合成一个列表，以便模型理解上下文，并延续之前的话题进行问答。

<span aceTableMode="list" aceTableWidth="1,5,5"></span>
|传入方式 |手动管理上下文 |通过ID管理上下文 |
|---|---|---|
|使用示例 |```JSON|```JSON|\
| |...|...|\
| |    "model": "doubao-seed-2-0-lite-260215",|    "model": "doubao-seed-2-0-lite-260215",|\
| |    "messages":[|    "previous_response_id":"<id>",|\
| |        {"role": "user", "content": "Hi, tell a joke."},|    "input": "What is the punchline of this joke?"|\
| |        {"role": "assistant", "content": "Why did the math book look sad? Because it had too many problems! 😄"},|...|\
| |        {"role": "user", "content": "What's the punchline of this joke?"}|```|\
| |    ]| |\
| |...| |\
| |```| |\
| | | |
|API |[Chat API](https://www.volcengine.com/docs/82379/1494384) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |

> 更多说明及完整示例请参见 [上下文管理](/docs/82379/2123288)。

<span id="78d5cc11"></span>
## 流式输出

<span aceTableMode="list" aceTableWidth="2,1"></span>
|预览 |优势 |
|---|---|
|<video src="https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/0b0ed47ec1b94b20a4f4966aa80130e6~tplv-goo7wpa0wc-image.image" controls></video>|* **改善等待体验**：无需等待完整内容生成完毕，可立即处理过程内容。|\
| |* **实时过程反馈**：多轮交互场景，实时了解任务当前的处理阶段。|\
| |* **更高的容错性**：中途出错，也能获取到已生成内容，避免非流式输出失败无返回的情况。|\
| |* **简化超时管理**：保持客户端与服务端的连接状态，避免复杂任务耗时过长而连接超时。 |

通过配置 **stream** 为 `true`，来启用流式输出。
```JSON
...
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {"role": "user", "content": "深度思考模型与非深度思考模型区别"}
    ],
    "stream": true
 ...
```

> 完整示例及更多说明请参见 [流式输出](/docs/82379/2123275)。

<span id="3821b26a"></span>
## 设置最大回答
&nbsp;
当控制成本或者回答问题时间，可通过限制模型回答长度实现。当回答篇幅较长，如翻译长文本，避免中途截断，可通过设置`max_tokens`更大值实现。
```JSON
...
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {"role": "user","content": "What are some common cruciferous plants?"}
    ],
    "max_tokens": 300
...
```

> 完整示例代码，请参见 [控制回答长度](/docs/82379/2123288#c7fbdbe3)。

<span id="8783d86f"></span>
## 异步输出
当任务较为复杂或者多个任务并发等场景下，可使用 Asyncio 接口实现并发调用，提高程序的效率，优化体验。

* Chat API 代码示例：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="WI8hcbF1cX"><RenderMd content={`\`\`\`Python
import asyncio
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import AsyncArk

# 初始化Ark客户端
client = AsyncArk(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey 
    api_key=os.getenv('ARK_API_KEY'), 
)

async def main() -> None:
    stream = await client.chat.completions.create(  
        # Replace with Model ID
        model = "doubao-seed-2-0-lite-260215",
        messages=[
            \{"role": "system", "content": "你是 AI 人工智能助手"\},
            \{"role": "user", "content": "常见的十字花科植物有哪些？"\},
        ],
        stream=True
    )
    async for completion in stream:
        print(completion.choices[0].delta.content, end="")
    print()
    
if __name__ == "__main__":
    asyncio.run(main())
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* Responses API 代码示例：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="VsAtkbR58o"><RenderMd content={`\`\`\`Python
import asyncio
import os
from volcenginesdkarkruntime import AsyncArk
from volcenginesdkarkruntime.types.responses.response_completed_event import ResponseCompletedEvent
from volcenginesdkarkruntime.types.responses.response_reasoning_summary_text_delta_event import ResponseReasoningSummaryTextDeltaEvent
from volcenginesdkarkruntime.types.responses.response_output_item_added_event import ResponseOutputItemAddedEvent
from volcenginesdkarkruntime.types.responses.response_text_delta_event import ResponseTextDeltaEvent
from volcenginesdkarkruntime.types.responses.response_text_done_event import ResponseTextDoneEvent


client = AsyncArk(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY')
)

async def main():
    stream = await client.responses.create(
        model="doubao-seed-2-0-lite-260215",
        input=[
            \{"role": "system", "content": "你是 AI 人工智能助手"\},
            \{"role": "user", "content": "常见的十字花科植物有哪些？"\},
        ],
        stream=True
    )
    async for event in stream:
        if isinstance(event, ResponseReasoningSummaryTextDeltaEvent):
            print(event.delta, end="")
        if isinstance(event, ResponseOutputItemAddedEvent):
            print("\\noutPutItem " + event.type + " start:")
        if isinstance(event, ResponseTextDeltaEvent):
            print(event.delta,end="")
        if isinstance(event, ResponseTextDoneEvent):
            print("\\noutPutTextDone.")
        if isinstance(event, ResponseCompletedEvent):
            print("Response Completed. Usage = " + event.response.usage.model_dump_json())


if __name__ == "__main__":
    asyncio.run(main())
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="10b8a01c"></span>
# 更多使用
<span id="a1d6b42a"></span>
## 深度思考
模型在输出回答前，先对输入问题进行系统性分析与逻辑拆解，再基于拆解结果生成回答。
可以显著提升回复质量，但会增加 token 消耗，详细信息请参见[深度思考](/docs/82379/1449737)。
<span id="19b5e705"></span>
## 提示词工程
正确设计和编写提示词，如提供说明、示例、好的规范等方法可提高模型输出的质量和准确性。进行提示词优化的工作也被称为提示词工程（Prompt Engineering）。详细信息请参见[提示词工程](/docs/82379/1221660)。
<span id="39a7195c"></span>
## 工具调用
通过集成内置工具或连接远程 MCP 服务器，您可以扩展模型的功能，以便更好回答问题或执行任务。当前支持：

* 内置工具：搜索网络、检索数据、图片处理等。
* 调用自定义函数。
* 访问三方MCP服务。

详细信息请参见[工具概述](/docs/82379/1827538)。
<span id="8d0362b6"></span>
## 续写模式
通过预填（Prefill）部分 **assistant** 角色的内容，引导和控制模型从已有的文本片段继续输出，以及控制模型在角色扮演场景中保持一致性。

* [续写模式](/docs/82379/1359497)：使用[Chat API](https://www.volcengine.com/docs/82379/1494384)实现续写模式。
* [续写模式](/docs/82379/1958520#a1384090)：使用[Responses API](https://www.volcengine.com/docs/82379/1569618)实现续写模式。

<span id="c22bed1a"></span>
## 结构化输出（beta）
控制模型输出程序可处理的标准格式（主要是 JSON）而非自然语言，方便标准化处理或展示。

* [结构化输出(beta)](/docs/82379/1568221)：使用[Chat API](https://www.volcengine.com/docs/82379/1494384)实现结构化输出。
* [结构化输出(beta)](/docs/82379/1568221)：使用[Responses API](https://www.volcengine.com/docs/82379/1569618)实现结构化输出。

<span id="4f8038b1"></span>
## 批量推理
方舟为您提供批量推理的能力，当您有大批量数据处理任务，可使用批量推理能力，以获得更大吞吐量和更低的成本。详细介绍和使用，请参见 [批量推理](/docs/82379/1399517)。
<span id="3b458a44"></span>
## 异常处理
增加异常处理，帮助定位问题。

```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="bL8GphGze1"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark
from volcenginesdkarkruntime._exceptions import ArkAPIError

# 初始化Ark客户端
client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3",    
    api_key=os.getenv('ARK_API_KEY'), 
)

# Streaming
try:
    stream = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
        messages=[
            \{"role": "system", "content": "你是 AI 人工智能助手"\},
            \{"role": "user", "content": "常见的十字花科植物有哪些？"\},
        ],
        stream=True
    )
    for chunk in stream:
        if not chunk.choices:
            continue

        print(chunk.choices[0].delta.content, end="")
    print()
except ArkAPIError as e:
    print(e)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="ySXZ7WkpA0"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "errors"
    "fmt"
    "io"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() \{
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    fmt.Println("----- streaming request -----")
    req := model.CreateChatCompletionRequest\{
        // Replace with Model ID
       Model: "doubao-seed-2-0-lite-260215",
       Messages: []*model.ChatCompletionMessage\{
          \{
             Role: model.ChatMessageRoleSystem,
             Content: &model.ChatCompletionMessageContent\{
                StringValue: volcengine.String("你是 AI 人工智能助手"),
             \},
          \},
          \{
             Role: model.ChatMessageRoleUser,
             Content: &model.ChatCompletionMessageContent\{
                StringValue: volcengine.String("常见的十字花科植物有哪些？"),
             \},
          \},
       \},
    \}
    stream, err := client.CreateChatCompletionStream(ctx, req)
    if err != nil \{
       apiErr := &model.APIError\{\}
       if errors.As(err, &apiErr) \{
          fmt.Printf("stream chat error: %v\\n", apiErr)
       \}
       return
    \}
    defer stream.Close()

    for \{
       recv, err := stream.Recv()
       if err == io.EOF \{
          return
       \}
       if err != nil \{
          apiErr := &model.APIError\{\}
          if errors.As(err, &apiErr) \{
             fmt.Printf("stream chat error: %v\\n", apiErr)
          \}
          return
       \}

       if len(recv.Choices) > 0 \{
          fmt.Print(recv.Choices[0].Delta.Content)
       \}
    \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="YvlElhDcZ7"><RenderMd content={`\`\`\`java
package com.volcengine.ark.runtime;

import com.volcengine.ark.runtime.exception.ArkHttpException;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.ArrayList;
import java.util.List;


public class ChatCompletionsExample \{
    public static void main(String[] args) \{

        String apiKey = System.getenv("ARK_API_KEY");
        // The base URL for model invocation
        ArkService service = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        System.out.println("----- streaming request -----");
        final List<ChatMessage> streamMessages = new ArrayList<>();
        final ChatMessage streamSystemMessage = ChatMessage.builder().role(ChatMessageRole.SYSTEM).content("你是 AI 人工智能助手").build();
        final ChatMessage streamUserMessage = ChatMessage.builder().role(ChatMessageRole.USER).content("常见的十字花科植物有哪些？").build();
        streamMessages.add(streamSystemMessage);
        streamMessages.add(streamUserMessage);

        ChatCompletionRequest streamChatCompletionRequest = ChatCompletionRequest.builder()
               .model("doubao-seed-2-0-lite-260215")//Replace with Model ID
               .messages(streamMessages)
               .build();

        try \{
            service.streamChatCompletion(streamChatCompletionRequest)
                   .doOnError(Throwable::printStackTrace)
                   .blockingForEach(
                            choice -> \{
                                if (choice.getChoices().size() > 0) \{
                                    System.out.print(choice.getChoices().get(0).getMessage().getContent());
                                \}
                            \}
                    );
        \} catch (ArkHttpException e) \{
            System.out.print(e.toString());
        \}

        // shutdown service
        service.shutdownExecutor();
    \}

\}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="b411f06e"></span>
## 对话加密
除了默认的网络层加密，火山方舟还提供免费的应用层加密功能，为您的推理会话数据提供更强的安全保护。您只需增加一行代码即可启用。完整示例代码请参见 [加密数据](/docs/82379/1544136#23274b89)；更多原理信息，请参见[推理会话数据应用层加密方案](/docs/82379/1389905)。
<span id="ca2551d7"></span>
# 使用说明

* 模型关键限制：
    * 最大上下文长度（Context Window）：即单次请求模型能处理的内容长度，包括用户输入和模型输出，单位 token 。超出最大上下文长度的内容时，会截断并停止输出。如碰到上下文限制导致的内容截断，可选择支持更大上下文长度规格的模型。
    * 最大输出长度（Max Tokens）：即单次模型输出的内容的最大长度。如碰到这种情况，可参考[续写模式](/docs/82379/1359497)，通过多次续写回复，拼接出完整内容。
    * 每分钟处理内容量（TPM）：即账号下同模型（不区分版本）每分钟能处理的内容量限制，单位 token。如默认 TPM 限制无法满足您的业务，可通过[工单](https://console.volcengine.com/workorder/create?step=2&SubProductID=P00001166)联系售后提升配额。举例：某模型的 TPM 为 500w，一个主账号下创建的该模型的所有版本接入点共享此配额。
    * 每分钟处理请求数（RPM）：即账号下同模型（不区分版本）每分钟能处理的请求数上限，与上面 TPM 类似。如默认 RPM 限制无法满足您的业务，可通过[工单](https://console.volcengine.com/workorder/create?step=2&SubProductID=P00001166)联系售后提升配额。
    * 各模型详细的规格信息，请参见 [模型列表](/docs/82379/1330310)。
* 用量查询：
    * 对于某次请求 token 用量：可在返回的 **usage** 结构体中查看。
    * 输入/输出内容的 token 用量：可使用 [Tokenization API](https://www.volcengine.com/docs/82379/1528728) 或 [Token 计算器](https://console.volcengine.com/ark/region:ark+cn-beijing/tokenCalculator)来估算。
    * 账号/项目/接入点维度 token 用量：可在 [用量统计](https://console.volcengine.com/ark/region:ark+cn-beijing/usageTracking) 页面查看。

<span id="901dd971"></span>
# 常见问题
[常见问题](/docs/82379/1359411)\-[在线推理](/docs/82379/1359411#aa45e6c0)：在线推理的常见问题，如遇到错误，可尝试在这里找解决方案。


部分大模型具备图片视觉理解能力，支持本地文件和图片 URL 方式传入图片，适用于图片描述、分类、视觉定位等场景。
:::tip
方舟平台的新用户？获取 API Key 及 开通模型等准备工作，请参见 [快速入门](/docs/82379/1399008)。
:::
<span id="18cf565a"></span>
# 快速开始
通过图片 URL 方式传入模型快速体验图片理解效果，Responses API 示例代码如下。

<span aceTableMode="list" aceTableWidth="1,1"></span>
|输入 |输出预览 |
|---|---|
|<span>![图片](https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/e3b9829615f54bb88a91892e2b3fb1a3~tplv-goo7wpa0wc-image.image =1798x) </span>|* 思考：用户现在需要找支持输入图片的模型系列，看表格里的输入列中的图片那一行。表格里模型系列Doubao\-Seed\-1.8对应的输入图片列是√，其他DeepSeek\-V3.2和GLM\-4.7对应的输入图片都是×，所以答案应该是Doubao\-Seed\-1.8。|\
|> 支持输入图片的模型系列是哪个？ |* 回答：支持输入图片的模型系列是Doubao\-Seed\-1.8。 |


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="Vc60fhWLrF"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
-H "Authorization: Bearer $ARK_API_KEY" \\
-H 'Content-Type: application/json' \\
-d '{
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
                },
                {
                    "type": "input_text",
                    "text": "支持输入图片的模型系列是哪个？"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="TnzuqA95DF"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

api_key = os.getenv('ARK_API_KEY')

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [

                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                },
            ],
        }
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="Fwh2f4OZ8z"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    
    "github.com/samber/lo"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "Which model series supports image input?",
                    },
                },
            },
        },
    }

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
    })
    if err != nil {
        fmt.Printf("response error: %v\\n", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="StUb3w3BI5"><RenderMd content={`\`\`\`Java
package com.ark.example;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;


public class demo {
    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemImage.builder().imageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png").build())
                                        .addListItem(InputContentItemText.builder().text("Which model series supports image input?").build())
                                        .build()
                        ).build()
                ).build())
                .build();
        ResponseObject resp = arkService.createResponse(request);
        System.out.println(resp);

        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="OGkyRwzaVR"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

api_key = os.getenv('ARK_API_KEY')

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [

                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                },
            ],
        }
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="f8d6cc48"></span>
# 模型与API
支持的模型：

* 请参见[视觉理解能力](/docs/82379/1330310#ff5ef604)。

支持的 API：

* [Responses API](https://www.volcengine.com/docs/82379/1569618)：支持图片作为输入进行分析。支持文件路径上传进行图片理解，使用方式参见[文件路径上传（推荐）](/docs/82379/1362931#2c38c01b)。
* [Chat API](https://www.volcengine.com/docs/82379/1494384)：支持图片作为输入进行分析。

<span id="547c81e8"></span>
# 图片传入方式
支持的图片传入方式如下：

* 本地文件上传：
    * [文件路径上传（推荐）](/docs/82379/1362931#2c38c01b)：直接传入本地文件路径，文件大小不能超过 512 MB。
    * [Base64 编码传入](/docs/82379/1362931#477e51ce)：适用于图片文件体积较小的场景，单张图片小于 10 MB，请求体不能超过 64 MB。
* [图片 URL 传入](/docs/82379/1362931#d86010f4)：适用于图片文件已存在公网可访问 URL 的场景，单张图片小于 10 MB。

:::tip
Chat API 是无状态的，如需模型对同一张图片进行多轮理解，则每次请求时都需传入该图片信息。
:::
<span id="dbbdddbe"></span>
## 本地文件上传
<span id="2c38c01b"></span>
### 文件路径上传（推荐）
建议优先采用文件路径方式上传本地文件，该方式可以支持最大 512MB 文件的处理。（当前 Responses API 支持该方式）
直接向模型传入本地文件路径，会自动调用 Files API 完成文件上传，再调用 Responses API 进行图片分析。仅 Python SDK 和 Go SDK 支持该方式。具体示例如下：

> * 如果需要实时获取分析内容，或者要规避复杂任务引发的客户端超时失败问题，可采用流式输出的方式，使用方式可参见[示例代码](/docs/82379/2123275#9346c907)。
> * 支持直接使用 Files API 上传本地文件，具体请参见[文件输入(File API)](/docs/82379/1885708)。


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="giHLKsqBfs"><RenderMd content={`\`\`\`Python
import asyncio
import os
from volcenginesdkarkruntime import AsyncArk

client = AsyncArk(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY')
)
async def main():
    local_path = "/Users/doc/ark_demo_img_1.png"
    response = await client.responses.create(
        model="doubao-seed-2-0-lite-260215",
        input=[
            {"role": "user", "content": [
                {
                    "type": "input_image",
                    "image_url": f"file://{local_path}"  
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                }
            ]},
        ]
    )
    print(response)
if __name__ == "__main__":
    asyncio.run(main())
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="K6t4M3JFNa"><RenderMd content={`\`\`\`Go
package main
import (
    "context"
    "fmt"
    "os"

    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)
func main() {
    client := arkruntime.NewClientWithApiKey(
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()
    localPath := "/Users/doc/ark_demo_img_1.png"
    imagePath := "file://" + localPath
    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: volcengine.String(imagePath),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "Which model series supports image input?",
                    },
                },
            },
        },
    }
    createResponsesReq := &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
    }
    resp, err := client.CreateResponses(ctx, createResponsesReq)
    if err != nil {
        fmt.Printf("stream error: %v\\n", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="477e51ce"></span>
### Base64 编码传入
将本地文件转换为 Base64 编码字符串，然后提交给大模型。该方式适用于图片文件体积较小的情况，单张图片小于 10 MB，请求体不能超过 64MB。（Responses API 和 Chat API 都支持该方式。）
:::warning
将图片文件转换为Base64编码字符串，然后遵循`data:{mime_type};base64,{base64_data}`格式拼接，传入模型。

* `{mime_type}`：文件的媒体类型，需要与文件格式mime_type对应。支持的图片格式详细见[图片格式说明](/docs/82379/1362931#51efc45f)。
* `{base64_data}`：文件经过Base64编码后的字符串。


:::
<span aceTableMode="list" aceTableWidth="5,5"></span>
|[Chat API](https://www.volcengine.com/docs/82379/1494384) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |
|---|---|
|```Python|```Python|\
|...|...|\
|model="doubao-seed-2-0-lite-260215",|model="doubao-seed-2-0-lite-260215",|\
|messages=[|input=[|\
|    {|    {|\
|        "role": "user",|        "role": "user",|\
|        "content": [|        "content": [|\
|            {|            {|\
|                "type": "image_url",|                "type": "input_image",|\
|                "image_url": {|                "image_url": f"data:image/png;base64,{base64_image}"|\
|                    "url": f"data:image/png;base64,{base64_image}"|            },|\
|                }|            {|\
|            },|                "type": "input_text",|\
|            {|                "text": "Which model series supports image input?"|\
|                "type": "text",|            }|\
|                "text": "Which model series supports image input?"|        ]|\
|            }|    }|\
|        ]|]|\
|    }|...|\
|]|```|\
|...| |\
|```| |\
| | |


* Responses API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="COZh9YV5Ym"><RenderMd content={`\`\`\`Bash
BASE64_IMAGE=$(base64 < demo.png) && curl https://ark.cn-beijing.volces.com/api/v3/responses \\
   -H "Content-Type: application/json"  \\
   -H "Authorization: Bearer $ARK_API_KEY"  \\
   -d @- <<EOF
   {
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
      {
        "role": "user",
        "content": [
          {
            "type": "input_image",
            "image_url": "data:image/png;base64,$BASE64_IMAGE"
          },
          {
            "type": "input_text",
            "text": "Which model series supports image input?"
          }
        ]
      }
    ]
  }
EOF
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="YYCa31yhBP"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark
import base64
# Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
api_key = os.getenv('ARK_API_KEY')

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)
# Convert local files to Base64-encoded strings.
def encode_file(file_path):
  with open(file_path, "rb") as read_file:
    return base64.b64encode(read_file.read()).decode('utf-8')
base64_file = encode_file("/Users/doc/demo.png")

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [

                {
                    "type": "input_image",
                    "image_url": f"data:image/png;base64,{base64_file}"
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                },
            ],
        }
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="onnugvooUX"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "encoding/base64"
    "fmt"
    "os"

    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() {
    // Convert local files to Base64-encoded strings.
    fileBytes, err := os.ReadFile("/Users/doc/demo.png") 
    if err != nil {
        fmt.Printf("read file error: %v\\n", err)
        return
    }
    base64File := base64.StdEncoding.EncodeToString(fileBytes)
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: fmt.Sprintf("data:image/png;base64,%s", base64File),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "Which model series supports image input?",
                    },
                },
            },
        },
    }

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
    })
    if err != nil {
        fmt.Printf("response error: %v\\n", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="TRo460iQKP"><RenderMd content={`\`\`\`Java
package com.ark.sample;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.io.IOException;

public class demo {
    private static String encodeFile(String filePath) throws IOException {
        byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
        return Base64.getEncoder().encodeToString(fileBytes);
    }
    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();
        // Convert local files to Base64-encoded strings.
        String base64Data = "";
        try {
            base64Data = "data:image/png;base64," + encodeFile("/Users/demo.png");
        } catch (IOException e) {
            System.err.println("encode error: " + e.getMessage());
        }
        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemImage.builder().imageUrl(base64Data).build())
                                        .addListItem(InputContentItemText.builder().text("Which model series supports image input?").build())
                                        .build()
                        ).build()
                ).build())
                .build();
        ResponseObject resp = arkService.createResponse(request);
        System.out.println(resp);

        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="sbty81goXU"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI
import base64
api_key = os.getenv('ARK_API_KEY')

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)
# Convert local files to Base64-encoded strings.
def encode_file(file_path):
  with open(file_path, "rb") as read_file:
    return base64.b64encode(read_file.read()).decode('utf-8')
base64_file = encode_file("/Users/doc/demo.png")

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [

                {
                    "type": "input_image",
                    "image_url": f"data:image/png;base64,{base64_file}",
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                },
            ],
        }
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* Chat API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="KKfunwqlX7"><RenderMd content={`\`\`\`Bash
BASE64_IMAGE=$(base64 < demo.png) && curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
   -H "Content-Type: application/json"  \\
   -H "Authorization: Bearer $ARK_API_KEY"  \\
   -d @- <<EOF
   {
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
      {
        "role": "user",
        "content": [
          {
            "type": "image_url",
            "image_url": {
              "url": "data:image/png;base64,$BASE64_IMAGE"
            }
          },
          {
            "type": "text",
            "text": "Which model series supports image input?"
          }
        ]
      }
    ],
    "max_tokens": 300
  }
EOF
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="lqNi5N3GAh"><RenderMd content={`\`\`\`Python
import base64
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)

# 定义方法将指定路径图片转为Base64编码
def encode_image(image_path):
  with open(image_path, "rb") as image_file:
    return base64.b64encode(image_file.read()).decode('utf-8')

# 需传给大模型的图片
image_path = "demo.png"

# 将图片转为Base64编码
base64_image = encode_image(image_path)

completion = client.chat.completions.create(
  # Replace with Model ID
  model = "doubao-seed-2-0-lite-260215",
  messages=[
    {
      "role": "user",
      "content": [
        {
          "type": "image_url",
          "image_url": {
          # 需注意：传入Base64编码遵循格式 data:image/<IMAGE_FORMAT>;base64,{base64_image}：
          # PNG图片："url":  f"data:image/png;base64,{base64_image}"
          # JPEG图片："url":  f"data:image/jpeg;base64,{base64_image}"
          # WEBP图片："url":  f"data:image/webp;base64,{base64_image}"
            "url":  f"data:image/<IMAGE_FORMAT>;base64,{base64_image}"
          },         
        },
        {
          "type": "text",
          "text": "Which model series supports image input?",
        },
      ],
    }
  ],
)

print(completion.choices[0])
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="s9BCZ2OTTm"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "encoding/base64"
    "fmt"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() {
    // 读取本地图片文件
    imageBytes, err := os.ReadFile("demo.png") // 替换为实际图片路径
    if err != nil {
        fmt.Printf("读取图片失败: %v\\n", err)
        return
    }
    base64Image := base64.StdEncoding.EncodeToString(imageBytes)

    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation  .
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
        )
    ctx := context.Background()
    req := model.CreateChatCompletionRequest{
        // Replace with Model ID
        Model: "doubao-seed-2-0-lite-260215",
        Messages: []*model.ChatCompletionMessage{
            {
                Role: "user",
                Content: &model.ChatCompletionMessageContent{
                    ListValue: []*model.ChatCompletionMessageContentPart{
                        {
                            Type: "image_url",
                            ImageURL: &model.ChatMessageImageURL{
                                URL: fmt.Sprintf("data:image/png;base64,%s", base64Image),
                            },
                        },
                        {
                            Type: "text",
                            Text: "Which model series supports image input?",
                        },
                    },
                },
            },
        },
    }

    resp, err := client.CreateChatCompletion(ctx, req)
    if err != nil {
        fmt.Printf("standard chat error: %v\\n", err)
        return
    }
    fmt.Println(*resp.Choices[0].Message.Content.StringValue)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="mKOrJRSsxi"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.completion.chat.*;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart.*;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.io.IOException;

public class Sample {
    static String apiKey = System.getenv("ARK_API_KEY");
    static ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
    static Dispatcher dispatcher = new Dispatcher();
    static ArkService service = ArkService.builder()
         .dispatcher(dispatcher)
         .connectionPool(connectionPool)
         .baseUrl("https://ark.cn-beijing.volces.com/api/v3") // The base URL for model invocation  .
         .apiKey(apiKey)
         .build();

    // Base64编码方法
    private static String encodeImage(String imagePath) throws IOException {
        byte[] imageBytes = Files.readAllBytes(Path.of(imagePath));
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    public static void main(String[] args) throws Exception {

        List<ChatMessage> messagesForReqList = new ArrayList<>();

        // 本地图片路径（替换为实际路径）
        String imagePath = "demo.png";

        // 生成Base64数据URL
        String base64Data = "data:image/png;base64," + encodeImage(imagePath);

        // 构建消息内容（修复内容部分构建方式）
        List<ChatCompletionContentPart> contentParts = new ArrayList<>();

        // 图片部分使用builder模式
        contentParts.add(ChatCompletionContentPart.builder()
                 .type("image_url")
                 .imageUrl(new ChatCompletionContentPartImageURL(base64Data))
                 .build());

        // 文本部分使用builder模式
        contentParts.add(ChatCompletionContentPart.builder()
                 .type("text")
                 .text("Which model series supports image input?")
                 .build());

        // 创建消息
        messagesForReqList.add(ChatMessage.builder()
                 .role(ChatMessageRole.USER)
                 .multiContent(contentParts)
                 .build());

        ChatCompletionRequest req = ChatCompletionRequest.builder()
                 .model("doubao-seed-2-0-lite-260215") //Replace with Model ID  .
                 .messages(messagesForReqList)
                 .maxTokens(300)
                 .build();

        service.createChatCompletion(req)
                 .getChoices()
                 .forEach(choice -> System.out.println(choice.getMessage().getContent()));
        // shutdown service after all requests are finished
        service.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="d86010f4"></span>
## 图片 URL 传入
如果图片已存在公网可访问URL，可以在请求中直接填入图片的公网URL，单张图片不能超过 10 MB。（Responses API 和 Chat API 都支持该方式。）
:::tip
如果使用 URL，建议使用火山引擎TOS（对象存储）存储图片并生成访问链接，不仅能保证图片的稳定存储，还能利用方舟与TOS的内网通信优势，有效降低模型回复的时延和公网流量费用。

:::
<span aceTableMode="list" aceTableWidth="5,5"></span>
|[Chat API](https://www.volcengine.com/docs/82379/1494384) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |
|---|---|
|```Python|```Python|\
|...|...|\
|model="doubao-seed-2-0-lite-260215",|model="doubao-seed-2-0-lite-260215",|\
|messages=[|input=[|\
|    {|    {|\
|        "role": "user",|        "role": "user",|\
|        "content": [|        "content": [|\
|            {|            {|\
|                "type": "image_url",|                "type": "input_image",|\
|                "image_url": {|                "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"|\
|                    "url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"|            },|\
|                }|            {|\
|            },|                "type": "input_text",|\
|            {|                "text": "Which model series supports image input?"|\
|                "type": "text",|            }|\
|                "text": "Which model series supports image input?"|        ]|\
|            }|    }|\
|        ]|]|\
|    }|...|\
|]|```|\
|...| |\
|```| |\
| | |


* Responses API 示例代码：[快速开始](/docs/82379/1362931#18cf565a)
* Chat API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="K7CnJIag6j"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
   -H "Content-Type: application/json" \\
   -H "Authorization: Bearer $ARK_API_KEY" \\
   -d '{
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {
            "role": "user",
            "content": [                
                {"type": "image_url","image_url": {"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"}},
                {"type": "text", "text": "Which model series supports image input?"}
            ]
        }
    ],
    "max_tokens": 300
  }'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="HWRAJpbs3V"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        {
            "role": "user",
            "content": [                
                {"type": "image_url","image_url": {"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"}},
                {"type": "text", "text": "Which model series supports image input?"},
            ],
        }
    ],
)

print(completion.choices[0])
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="e0EaAFHbYB"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        //Use os.Getenv to get ARK_API_KEY
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    // Create a context background 
    ctx := context.Background()
    // Construct the content of the message
    contentParts := []*model.ChatCompletionMessageContentPart{
        // Image
        {
            Type: "image_url",
            ImageURL: &model.ChatMessageImageURL{
                URL: "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
            },
        },
        // Text
        {
            Type: "text",
            Text: "Which model series supports image input?",
        },
    }
    // Construct chat, specify model and message
    req := model.CreateChatCompletionRequest{
        // Replace with Model ID
       Model: "doubao-seed-2-0-lite-260215",
       Messages: []*model.ChatCompletionMessage{
          {
             // Set message role as user
             Role: model.ChatMessageRoleUser,
             Content: &model.ChatCompletionMessageContent{
                ListValue: contentParts,
             },
          },
       },
    }

    // Send chat, store result in resp and any possible error in err
    resp, err := client.CreateChatCompletion(ctx, req)
    if err!= nil {
       fmt.Printf("standard chat error: %v\\n", err)
       return
    }
    // Print response
    fmt.Println(*resp.Choices[0].Message.Content.StringValue)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="zpODxHugnf"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.completion.chat.*;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart.*;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;

public class MultiImageSample {
  static String apiKey = System.getenv("ARK_API_KEY");
  static ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
  static Dispatcher dispatcher = new Dispatcher();
  static ArkService service = ArkService.builder()
       .dispatcher(dispatcher)
       .connectionPool(connectionPool)
       .baseUrl("https://ark.cn-beijing.volces.com/api/v3")  // The base URL for model invocation  .
       .apiKey(apiKey)
       .build();

  public static void main(String[] args) throws Exception {

    List<ChatMessage> messagesForReqList = new ArrayList<>();

    // Construct the content of the message
    List<ChatCompletionContentPart> contentParts = new ArrayList<>();

    // Use builder mode for the image
    contentParts.add(ChatCompletionContentPart.builder()
         .type("image_url")
         .imageUrl(new ChatCompletionContentPartImageURL(
            "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"))
         .build());

    // Use builder mode for text
    contentParts.add(ChatCompletionContentPart.builder()
         .type("text")
         .text("Which model series supports image input?")
         .build());

    // Create message
    messagesForReqList.add(ChatMessage.builder()
         .role(ChatMessageRole.USER)
         .multiContent(contentParts)
         .build());

    ChatCompletionRequest req = ChatCompletionRequest.builder()
         .model("doubao-seed-2-0-lite-260215") //Replace with Model ID  .
         .messages(messagesForReqList)
         .build();

    service.createChatCompletion(req)
         .getChoices()
         .forEach(choice -> System.out.println(choice.getMessage().getContent()));
    // shutdown service after all requests are finished
    service.shutdownExecutor();
  }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="2d7ef2c7"></span>
# 使用场景
<span id="594387aa"></span>
## 多图输入
API 可支持接受和处理多个图像输入，这些图像可通过图片可访问 URL 或图片转为 Base64 编码后输入，模型将结合所有传入的图像中的信息来回答问题。

* Responses API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="F3dFTD7QZP"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
  -H "Authorization: Bearer $ARK_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_2.png"
                },
                {
                    "type": "input_text",
                    "text": "支持输入图片的模型系列是哪个？同时，豆包应用场景有哪些？"
                }
            ]
        }
    ]
  }'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="RKfiy3j7s4"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY')
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_2.png"
                },
                {
                    "type": "input_text",
                    "text": "支持输入图片的模型系列是哪个？同时，豆包应用场景有哪些？"
                }
            ]
        }
    ]
)

print(response.output)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="VfJfLmiVBn"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"

    "github.com/samber/lo"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_2.png"),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "支持输入图片的模型系列是哪个？同时，豆包应用场景有哪些？",
                    },
                },
            },
        },
    }

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
    })
    if err != nil {
        fmt.Printf("response error: %v\\n", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="aoCGq7PZiQ"><RenderMd content={`\`\`\`Java
package com.ark.sample;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;


public class demo {
    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemImage.builder().imageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png").build())
                                        .addListItem(InputContentItemImage.builder().imageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_2.png").build())
                                        .addListItem(InputContentItemText.builder().text("支持输入图片的模型系列是哪个？同时，豆包应用场景有哪些？").build())
                                        .build()
                        ).build()
                ).build())
                .build();
        ResponseObject resp = arkService.createResponse(request);
        System.out.println(resp);

        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* Chat API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="pzP0teT28S"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
   -H "Content-Type: application/json"  \\
   -H "Authorization: Bearer $ARK_API_KEY"  \\
   -d '{
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {
            "role": "user",
            "content": [                
                {"type": "image_url","image_url": {"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"}},
                {"type": "image_url","image_url": {"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_2.png"}},
                {"type": "text", "text": "支持输入图片的模型系列是哪个？同时，豆包应用场景有哪些？"}
            ]
        }
    ],
    "max_tokens": 300
  }'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="O5JMnq7TIC"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        {
            "role": "user",
            "content": [                
                {"type": "image_url","image_url": {"url":  "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"}},
                {"type": "image_url","image_url": {"url":  "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_2.png"}},
                {"type": "text", "text": "支持输入图片的模型系列是哪个？同时，豆包应用场景有哪些？"},
            ],
        }
    ],
)

print(completion.choices[0])
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="VAAQHXNGOX"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        //Use os.Getenv to get ARK_API_KEY
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    // Create context background
    ctx := context.Background()
    // Construct message, including 2 images and a text
    contentParts := []*model.ChatCompletionMessageContentPart{
        // First image
        {
            Type: "image_url",
            ImageURL: &model.ChatMessageImageURL{
                URL: "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png",
            },
        },
        // Second image
        {
            Type: "image_url",
            ImageURL: &model.ChatMessageImageURL{
                URL: "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_2.png",
            },
        },
        // Text
        {
            Type: "text",
            Text: "支持输入图片的模型系列是哪个？同时，豆包应用场景有哪些？",
        },
    }
    // Construct chat request
    req := model.CreateChatCompletionRequest{
        // Replace with Model ID
       Model: "doubao-seed-2-0-lite-260215",
       Messages: []*model.ChatCompletionMessage{
          {
             // Set message role as user
             Role: model.ChatMessageRoleUser,
             Content: &model.ChatCompletionMessageContent{
                ListValue: contentParts, // Use ListValue for multi-type content
             },
          },
       },
       MaxTokens: volcengine.Int(300), // Set max output token count
    }

    // Send the chat completion request
    resp, err := client.CreateChatCompletion(ctx, req)
    if err!= nil {
       fmt.Printf("standard chat error: %v\\n", err)
       return
    }
    // Print response
    fmt.Println(*resp.Choices[0].Message.Content.StringValue)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="w8dwaenHGi"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.completion.chat.*;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart.*;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;

public class MultiImageSample {
  static String apiKey = System.getenv("ARK_API_KEY");
  static ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
  static Dispatcher dispatcher = new Dispatcher();
  static ArkService service = ArkService.builder()
       .dispatcher(dispatcher)
       .connectionPool(connectionPool)
       .baseUrl("https://ark.cn-beijing.volces.com/api/v3") // The base URL for model invocation
       .apiKey(apiKey)
       .build();

  public static void main(String[] args) throws Exception {

    List<ChatMessage> messagesForReqList = new ArrayList<>();

    // Construct content of the message
    List<ChatCompletionContentPart> contentParts = new ArrayList<>();

    // Use builder mode for the first image
    contentParts.add(ChatCompletionContentPart.builder()
         .type("image_url")
         .imageUrl(new ChatCompletionContentPartImageURL(
            "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"))
         .build());

    // 
    contentParts.add(ChatCompletionContentPart.builder()
         .type("image_url")
         .imageUrl(new ChatCompletionContentPartImageURL(
            "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_2.png"))
         .build());

    contentParts.add(ChatCompletionContentPart.builder()
         .type("text")
         .text("支持输入图片的模型系列是哪个？同时，豆包应用场景有哪些？")
         .build());

    messagesForReqList.add(ChatMessage.builder()
         .role(ChatMessageRole.USER)
         .multiContent(contentParts)
         .build());

    ChatCompletionRequest req = ChatCompletionRequest.builder()
         .model("doubao-seed-2-0-lite-260215") //Replace with Model ID
         .messages(messagesForReqList)
         .maxTokens(300)
         .build();

    service.createChatCompletion(req)
         .getChoices()
         .forEach(choice -> System.out.println(choice.getMessage().getContent()));
    // shutdown service after all requests are finished
    service.shutdownExecutor();
  }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="bf4d9224"></span>
## 控制图片理解的精细度
控制图片理解的精细度（指对画面的精细）： **image_pixel_limit 、detail** 字段，2个字段若同时配置，则生效逻辑如下：

* 生效前提：图片像素范围在 [196, 36,000,000] px，否则直接报错。
* 生效优先级：**image_pixel_limit** 高于 **detail** 字段，即同时配置 **detail** 与 **image_pixel_limit** 字段时，生效 **image_pixel_limit** 字段配置。
* 缺省时生效：**image_pixel_limit** 字段的 **min_pixels** / **max_pixels** 字段未设置，则使用 **detail** 默认值配置所对应的值。具体范围参见[通过 detail 字段（图片理解）](/docs/82379/1362931#885d96dc)。

下面分别介绍如何通过 **detail** 、 **image_pixel_limit** 控制视觉理解的精度。
<span id="885d96dc"></span>
### 通过 detail 字段（图片理解）
通过`detail`参数来控制模型理解图片的精细度， 不同模型支持的 detail 模式、token 用量、图片像素区间如下：
:::tip
doubao\-seed\-2.0 模型 detail 默认值为 `high`，单图固定 1280 个 tokens，在不牺牲效果的同时消耗的 tokens 更少。

:::
<span aceTableMode="list" aceTableWidth="2,2.5,3,2.5,3,3,3"></span>
|detail模式 |doubao\-seed\-1.8 之前的模型||doubao\-seed\-1.8 模型||doubao\-seed\-2.0 模型||\
| |> detail 默认值为`low` | |> detail 默认值为 `high` | |> detail 默认值为 `high` | |
|^^|单图token范围 |图片像素区间 |单图token范围 |图片像素区间 |单图token范围 |图片像素区间 |
|---|---|---|---|---|---|---|
|low |[4, 1312] |[3136, 1048576] |[1, 1213] |[1764, 2139732] |[1, 1280] |[1764, 2257920] |
|high |[4, 5120] |[3136, 4014080] |[1, 5120] |[1764, 9031680] |1280 |2257920 |
|xhigh |\- |\- |\- |\- |[1280, 5120] |[2257920, 9031680] |


* detail 为 `low` 时，图片处理速度会提高，适合图片本身细节较少或者只需模型理解图片大致信息或者对速度有要求的场景。
* detail 为 `high` 或 `xhigh` 时，模型可感知图片更多的细节，但是图片处理速度会降低，适合图像像素值高且需关注细节信息的场景，如街道地图分析等。

**图片缩放规则**：不在指定模式对应的图片像素区间时，方舟会等比例缩放至范围内。

* Responses API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="lFmh5pDItH"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
-H "Authorization: Bearer $ARK_API_KEY" \\
-H 'Content-Type: application/json' \\
-d '{
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png",
                    "detail": "high"
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="Ehy8r58Hmv"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY')
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png",
                    "detail": "high"
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                }
            ]
        }
    ]
)

print(response.output)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="ocPY0b3Pms"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"

    "github.com/samber/lo"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png"),
                        Detail:   lo.ToPtr(responses.ContentItemImageDetail_high),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "Which model series supports image input?",
                    },
                },
            },
        },
    }

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
    })
    if err != nil {
        fmt.Printf("response error: %v\\n", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="IbvlPidJni"><RenderMd content={`\`\`\`Java
package com.ark.sample;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;


public class demo {
    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemImage.builder().imageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png").detail("high").build())
                                        .addListItem(InputContentItemText.builder().text("Which model series supports image input?").build())
                                        .build()
                        ).build()
                ).build())
                .build();
        ResponseObject resp = arkService.createResponse(request);
        System.out.println(resp);

        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* Chat API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="xsYquExPJ2"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
   -H "Content-Type: application/json" \\
   -H "Authorization: Bearer $ARK_API_KEY" \\
   -d '{
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {
            "role": "user",
            "content": [                
                {"type": "image_url","image_url": {"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png","detail": "high"}},
                {"type": "text", "text": "Which model series supports image input?"}
            ]
        }
    ]
  }'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="WcVRkv1pTY"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        {
            "role": "user",
            "content": [                
                {"type": "image_url","image_url": {"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png","detail": "high"}},
                {"type": "text", "text": "Which model series supports image input?"},
            ],
        }
    ],
)

print(completion.choices[0])
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="Ceed1bCmOa"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        //Use os.Getenv to get ARK_API_KEY
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    // Construct a context background
    ctx := context.Background()
    // Message content
    contentParts := []*model.ChatCompletionMessageContentPart{
        // Image
        {
            Type: "image_url",
            ImageURL: &model.ChatMessageImageURL{
                URL: "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png",
                Detail: model.ImageURLDetailHigh,
            },
        },
        // Text
        {
            Type: "text",
            Text: "Which model series supports image input?",
        },
    }
    req := model.CreateChatCompletionRequest{
        // Replace with Model ID
       Model: "doubao-seed-2-0-lite-260215",
       Messages: []*model.ChatCompletionMessage{
          {
             Role: model.ChatMessageRoleUser,
             Content: &model.ChatCompletionMessageContent{
                ListValue: contentParts, // Use ListValue for multi-type content
             },
          },
       },
       MaxTokens: volcengine.Int(300), // Max output token
    }

    resp, err := client.CreateChatCompletion(ctx, req)
    if err!= nil {
       fmt.Printf("standard chat error: %v\\n", err)
       return
    }
    fmt.Println(*resp.Choices[0].Message.Content.StringValue)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="vSFmt1JvVX"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.completion.chat.*;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart.*;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;

public class MultiImageSample {
  static String apiKey = System.getenv("ARK_API_KEY");
  static ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
  static Dispatcher dispatcher = new Dispatcher();
  static ArkService service = ArkService.builder()
       .dispatcher(dispatcher)
       .connectionPool(connectionPool)
       .baseUrl("https://ark.cn-beijing.volces.com/api/v3")  // The base URL for model invocation  .
       .apiKey(apiKey)
       .build();

  public static void main(String[] args) throws Exception {

    List<ChatMessage> messagesForReqList = new ArrayList<>();

    List<ChatCompletionContentPart> contentParts = new ArrayList<>();

    contentParts.add(ChatCompletionContentPart.builder()
         .type("image_url")
         .imageUrl(new ChatCompletionContentPartImageURL(
            "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png","high"))
         .build());

    contentParts.add(ChatCompletionContentPart.builder()
         .type("text")
         .text("Which model series supports image input?")
         .build());

    messagesForReqList.add(ChatMessage.builder()
         .role(ChatMessageRole.USER)
         .multiContent(contentParts)
         .build());

    ChatCompletionRequest req = ChatCompletionRequest.builder()
         .model("doubao-seed-2-0-lite-260215") //Replace with Model ID  .
         .messages(messagesForReqList)
         .maxTokens(300)
         .build();

    service.createChatCompletion(req)
         .getChoices()
         .forEach(choice -> System.out.println(choice.getMessage().getContent()));
    // shutdown service after all requests are finished
    service.shutdownExecutor();
  }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="d2b576dd"></span>
### **通过 image_pixel_limit 结构体**
控制传入给方舟的图像像素大小范围，如果不在此范围，则会等比例放大或者缩小至该范围内，后传给模型进行理解。你可通过 **image_pixel_limit** 结构体，精细控制模型可理解的图片像素多少。
对应结构体如下：
```Bash
"image_pixel_limit": {
    "max_pixels": 3014080,   # 图片最大像素
    "min_pixels": 3136       # 图片最小像素
}
```

示例代码如下：
> Java SDK、 Go SDK 不支持此字段。

* Responses API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="vDyQU0wgkb"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
-H "Authorization: Bearer $ARK_API_KEY" \\
-H 'Content-Type: application/json' \\
-d '{
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png",
                    "image_pixel_limit":  {
                        "max_pixels": 3014080,
                        "min_pixels": 3136
                     }
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="fcWynoT0AK"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY')
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png",
                    "image_pixel_limit": {
                        "max_pixels": 3014080,
                        "min_pixels": 3136,
                    }
                },
                {
                    "type": "input_text",
                    "text": "Which model series supports image input?"
                }
            ]
        }
    ]
)

print(response.output)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* Chat API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="Wigv6XmrLr"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
   -H "Content-Type: application/json" \\
   -H "Authorization: Bearer $ARK_API_KEY" \\
   -d '{
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {
            "role": "user",
            "content": [                
                {"type": "image_url","image_url": {"url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png","image_pixel_limit": {"max_pixels": 3014080,"min_pixels": 3136}}},
                {"type": "text", "text": "Which model series supports image input?"}
            ]
        }
    ],
    "max_tokens": 300
  }'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="ENVYf82BPe"><RenderMd content={`\`\`\`Python
import os
# Install SDK: pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        {
            "role": "user",
            "content": [
                {
                    "type": "image_url",
                    "image_url": {
                        "url":  "https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png",
                        "image_pixel_limit": {
                            "max_pixels": 3014080,
                            "min_pixels": 3136,
                        },
                    },
                 },
                {"type": "text", "text": "Which model series supports image input?"},
            ],
        }
    ],
)

print(completion.choices[0])
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="474e4601"></span>
## 图文混排
支持灵活地传入提示词和图片信息的方式，你可任意调整传入图片和文本的顺序，以及在`system message`或者`User message`传入图文信息。模型会根据顺序返回处理信息的结果，示例如下。
:::tip
图文混排场景，图文顺序可能影响模型输出效果，若结果不符预期，可调整顺序。当多图+一段文字时，建议将文字放在图片之后。

:::
* Responses API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="Qax2vBVjwg"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
-H "Authorization: Bearer $ARK_API_KEY" \\
-H 'Content-Type: application/json' \\
-d '{
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
        {
            "role": "system",
            "content": [
                {
                    "type": "input_text",
                    "text": "下面人物是目标人物"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/target.png"
                },
                {
                    "type": "input_text",
                    "text": "请确认下面图片中是否含有目标人物"
                }
            ]
        },
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "图片1中是否含有目标人物"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_01.png"
                },
                {
                    "type": "input_text",
                    "text": "图片2中是否含有目标人物"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_02.png"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="S2EnlL44qX"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY')
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "system",
            "content": [
                {
                    "type": "input_text",
                    "text": "下面人物是目标人物"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/target.png"
                },
                {
                    "type": "input_text",
                    "text": "请确认下面图片中是否含有目标人物"
                }
            ]
        },
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "图片1中是否含有目标人物"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_01.png"
                },
                {
                    "type": "input_text",
                    "text": "图片2中是否含有目标人物"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_02.png"
                }
            ]
        }
    ]
)

print(response.output)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="M6OTCebvFM"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"

    "github.com/samber/lo"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    systemMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_system,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "下面人物是目标人物",
                    },
                },
            },
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_image/target.png"),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "请确认下面图片中是否含有目标人物",
                    },
                },
            },
        },
    }

    userMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "图片1中是否含有目标人物",
                    },
                },
            },
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_01.png"),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "图片2中是否含有目标人物",
                    },
                },
            },
            {
                Union: &responses.ContentItem_Image{
                    Image: &responses.ContentItemImage{
                        Type:     responses.ContentItemType_input_image,
                        ImageUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_02.png"),
                    },
                },
            },
        },
    }

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{
                    {
                        Union: &responses.InputItem_InputMessage{
                            InputMessage: systemMessage,
                        },
                    },
                    {
                        Union: &responses.InputItem_InputMessage{
                            InputMessage: userMessage,
                        },
                    },
                }},
            },
        },
    })
    if err != nil {
        fmt.Printf("response error: %v\\n", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="nIzNRMQ1LX"><RenderMd content={`\`\`\`Java
package com.ark.sample;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;


public class demo {
    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder()
                        .addListItem(ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_SYSTEM).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemText.builder().text("下面人物是目标人物").build())
                                        .addListItem(InputContentItemImage.builder().imageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/target.png").build())
                                        .addListItem(InputContentItemText.builder().text("请确认下面图片中是否含有目标人物").build())
                                        .build()
                        ).build())
                        .addListItem(ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemText.builder().text("图片1中是否含有目标人物").build())
                                        .addListItem(InputContentItemImage.builder().imageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_01.png").build())
                                        .addListItem(InputContentItemText.builder().text("图片2中是否含有目标人物").build())
                                        .addListItem(InputContentItemImage.builder().imageUrl("https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_02.png").build())
                                        .build()
                        ).build())
                        .build()
                ).build();

        ResponseObject resp = arkService.createResponse(request);
        System.out.println(resp);

        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* Chat API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="h0jQleySLd"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
   -H "Content-Type: application/json" \\
   -H "Authorization: Bearer $ARK_API_KEY" \\
   -d '{
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {
            "role": "system",
            "content": [
                {"type": "text", "text": "下面人物是目标人物"},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/target.png"
                    }
                },
                {"type": "text", "text": "请确认下面图片中是否含有目标人物"}
            ]
        },
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "图片1中是否含有目标人物"},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_01.png"
                    }
                },
                {"type": "text", "text": "图片2中是否含有目标人物"},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_02.png"
                    }
                }
            ]
        }
    ],
    "max_tokens": 300
  }'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="vAGZSfuQld"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        {
            "role": "system",
            "content": [
                {"type": "text", "text": "下面人物是目标人物"},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/target.png"
                    },
                },
                {"type": "text", "text": "请确认下面图片中是否含有目标人物"},
            ],
        },
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "图片1中是否含有目标人物"},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_01.png"
                    },
                },
                {"type": "text", "text": "图片2中是否含有目标人物"},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_02.png"
                    },
                },
            ],
        },
    ],
)


print(completion.choices[0].message.content)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="fwWUveShBC"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    // Create a context, typically used to pass request context information, such as timeouts and cancellations
  ctx := context.Background()

  // Build the system message content
  systemContentParts := []*model.ChatCompletionMessageContentPart{
    // Text content
    {
      Type: "text",
      Text: "下面人物是目标人物",
    },
    // Target person image
    {
      Type: "image_url",
      ImageURL: &model.ChatMessageImageURL{
        URL: "https://ark-project.tos-cn-beijing.volces.com/doc_image/target.png",
      },
    },
    // Text content
    {
      Type: "text",
      Text: "请确认下面图片中是否含有目标人物",
    },
  }

  // Build the user message content
  userContentParts := []*model.ChatCompletionMessageContentPart{
    // Text
    {
      Type: "text",
      Text: "图片1中是否含有目标人物",
    },
    // First scene image
    {
      Type: "image_url",
      ImageURL: &model.ChatMessageImageURL{
        URL: "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_01.png",
      },
    },
    // Text
    {
      Type: "text",
      Text: "图片2中是否含有目标人物",
    },
    // Second scene image
    {
      Type: "image_url",
      ImageURL: &model.ChatMessageImageURL{
        URL: "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_02.png",
      },
    },
  }

  // Build a chat completion request and set the model and message content
  req := model.CreateChatCompletionRequest{
    // Replace with Model ID
    Model: "doubao-seed-2-0-lite-260215",
    Messages: []*model.ChatCompletionMessage{
      {
        // The message role is system
        Role: model.ChatMessageRoleSystem,
        Content: &model.ChatCompletionMessageContent{
          ListValue: systemContentParts,
        },
      },
      {
        // The message role is user
        Role: model.ChatMessageRoleUser,
        Content: &model.ChatCompletionMessageContent{
          ListValue: userContentParts,
        },
      },
    },
    MaxTokens: volcengine.Int(300),
  }

    // Send the chat completion request, store the result in resp, and store any possible errors in err
    resp, err := client.CreateChatCompletion(ctx, req)
    if err!= nil {
       fmt.Printf("standard chat error: %v\\n", err)
       return
    }
    fmt.Println(*resp.Choices[0].Message.Content.StringValue)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="BHIriHJeeT"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.completion.chat.*;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart.*;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;

public class MultiImageSample {
  static String apiKey = System.getenv("ARK_API_KEY");
  static ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
  static Dispatcher dispatcher = new Dispatcher();
  static ArkService service = ArkService.builder()
       .dispatcher(dispatcher)
       .connectionPool(connectionPool)
       .baseUrl("https://ark.cn-beijing.volces.com/api/v3")  // The base URL for model invocation
       .apiKey(apiKey)
       .build();

  public static void main(String[] args) throws Exception {
    List<ChatMessage> messagesForReqList = new ArrayList<>();
    
    // Build the system message content
    List<ChatCompletionContentPart> systemContentParts = new ArrayList<>();
    systemContentParts.add(ChatCompletionContentPart.builder()
         .type("text")
         .text("下面人物是目标人物")
         .build());
    systemContentParts.add(ChatCompletionContentPart.builder()
         .type("image_url")
         .imageUrl(new ChatCompletionContentPartImageURL(
            "https://ark-project.tos-cn-beijing.volces.com/doc_image/target.png"))
         .build());
    systemContentParts.add(ChatCompletionContentPart.builder()
         .type("text")
         .text("请确认下面图片中是否含有目标人物")
         .build());

    // Create the system message
    messagesForReqList.add(ChatMessage.builder()
         .role(ChatMessageRole.SYSTEM)
         .multiContent(systemContentParts)
         .build());

    // Build the user message content
    List<ChatCompletionContentPart> userContentParts = new ArrayList<>();
    userContentParts.add(ChatCompletionContentPart.builder()
         .type("text")
         .text("图片1中是否含有目标人物")
         .build());
    userContentParts.add(ChatCompletionContentPart.builder()
         .type("image_url")
         .imageUrl(new ChatCompletionContentPartImageURL(
            "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_01.png"))
         .build());
    userContentParts.add(ChatCompletionContentPart.builder()
         .type("text")
         .text("图片2中是否含有目标人物")
         .build());
    userContentParts.add(ChatCompletionContentPart.builder()
         .type("image_url")
         .imageUrl(new ChatCompletionContentPartImageURL(
            "https://ark-project.tos-cn-beijing.volces.com/doc_image/scene_02.png"))
         .build());

    // Create user message
    messagesForReqList.add(ChatMessage.builder()
         .role(ChatMessageRole.USER)
         .multiContent(userContentParts)
         .build());
    ChatCompletionRequest req = ChatCompletionRequest.builder()
         .model("doubao-seed-2-0-lite-260215") //Replace with Model ID
         .messages(messagesForReqList)
         .maxTokens(300)
         .build();

    service.createChatCompletion(req)
         .getChoices()
         .forEach(choice -> System.out.println(choice.getMessage().getContent()));
    // shutdown service after all requests are finished
    service.shutdownExecutor();
  }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="5fdeb294"></span>
## 视觉定位（Visual Grounding）
请参见教程 [视觉定位 Grounding](/docs/82379/1616136)。
<span id="52afa2e1"></span>
## GUI任务处理
请参见教程 [GUI 任务处理](/docs/82379/1584296)。
<span id="7a123cd1"></span>
# 使用说明
:::tip
处理完图片/视频后，文件会从方舟服务器删除。方舟不会保留你提交的图片、视频以及文本信息等用户数据来训练模型。
:::
<span id="f141b9ef"></span>
## 图片像素说明

1. 传入图片像素要求如下，超出限制后会直接报错。
    * 宽 \> 14px 且高 \> 14px
    * 宽\*高范围：[196px, 36000000px]
    * 宽高比范围：[1/150, 150]
2. 图片预处理：
   根据使用的模型、设置的 detail 模式，将图片等比例缩放至相应的范围（具体见[通过 detail 字段（图片理解）](/docs/82379/1362931#885d96dc)），可降低模型响应时延及 token 消耗。

<span id="57188ace"></span>
## 图片 token 用量说明
根据图片宽高像素计算得到 token 用量。不同模型的图片 token 用量估算逻辑如下。单图 token 范围参见[通过 detail 字段（图片理解）](/docs/82379/1362931#885d96dc)。

<span aceTableMode="list" aceTableWidth="1,1"></span>
|doubao\-seed\-1.8 之前的模型 |doubao\-seed\-1.8 模型、doubao\-seed\-2.0 模型 |
|---|---|
|```JSON|```JSON|\
|min(image_width * image_hight ÷ 784, max_image_tokens)|min(image_width * image_hight ÷ 1764, max_image_tokens)|\
|```|```|\
| | |

以传入模型的单图 token 最大值为 1312 为例，计算图片消耗的 token 数的逻辑如下：

* 图片尺寸为 `1280 px × 720 px`：理解这张图消耗的 token 为`1280×720÷784=1176`，该值小于 1312，根据公式计算消耗 token 数为 1176。
* 图片尺寸为 `1920 px × 1080 px`：理解这张图消耗的 token 为`1920×1080÷784=2645`，该值大于 1312，根据公式计算消耗 token 数为 1312。
  这种情况会对图片进行压缩，即图片会丢失部分细节。譬如字体很小的图片，模型可能会无法识别文字内容。

<span id="4ecbf924"></span>
## 图片数量说明
单次请求传入图片数量受限于模型上下文窗口。当输入过长，触发模型上下文窗口，信息会被截断。
> 模型上下文窗口请参见[模型列表](/docs/82379/1330310)。
> 举例说明：
> * 当图片总像素值大，使用的模型上下文窗口为 32k token，每张图片转为 1312 token ，单次请求可传入的图片数量为 `32000 ÷ 1312 = 24`张。
> * 当图片总像素值小，使用的模型上下文窗口为 32k token，每张图片转为 256 token，单次请求可传入的数量为 `32000 ÷ 256 = 125` 张。

:::tip
模型回复的质量，受输入图片信息量影响。过多的图片会导致模型回复质量下滑，请合理控制单次请求传入图片的数量。
:::
<span id="3d62f9e9"></span>
## 图片文件容量
使用 URL 方式传入图片，单张图片不能超过 10MB。
使用 Base64 编码传入图片，单张图片不能超过 10MB，请求体不能超过 64MB。
使用文件路径传入图片，图片不能超过 512 MB。
<span id="51efc45f"></span>
## 图片格式说明
支持的图片格式如下表，注意文件后缀匹配图片格式，即图片文件扩展名（URL传入时）、图片格式声明（Base64 编码传入时）需与图片实际信息一致。

<span aceTableMode="list" aceTableWidth="1,1,2"></span>
|**图片格式** |**文件扩展名** |**内容格式** **Content Type** |
|---|---|---|
|JPEG |.jpg, .jpeg |`image/jpeg` |
|PNG |.png |`image/png` |
|GIF |.gif |`image/gif` |
|WEBP |.webp |`image/webp` |
|BMP |.bmp |`image/bmp` |
|TIFF |.tiff, .tif |`image/tiff` |
|ICO |.ico |`image/ico` |
|DIB |.dib |`image/bmp` |
|ICNS |.icns |`image/icns` |
|SGI |.sgi |`image/sgi` |
|JPEG2000 |.j2c, .j2k, .jp2, .jpc, .jpf, .jpx |`image/jp2` |
|HEIC |.heic |`image/heic`|\
| | |> doubao\-1.5\-vision\-pro及以后模型支持 |
|HEIF |.heif |`image/heif`|\
| | |> doubao\-1.5\-vision\-pro及以后模型支持 |

:::tip

* 上传文件至对象存储时设置，详情请参见[文档](https://www.volcengine.com/docs/6349/145523#%E8%AE%BE%E7%BD%AE%E6%96%87%E4%BB%B6%E5%85%83%E6%95%B0%E6%8D%AE)。
* 传入 Base64编码时使用：[Base64 编码输入](/docs/82379/1362931#f6222fec)。
* 图片格式需小写。
* TIFF、 SGI、ICNS、JPEG2000 几种格式图片，需保证和元数据对齐，如在对象存储中正确设置文件元数据，否则会解析失败，详细请参见 [使用视觉理解模型时，报错InvalidParameter？](/docs/82379/1359411#effccb14)

:::
<span id="c1f33d37"></span>
## API 参数字段说明
以下字段视觉理解暂不支持。

* 不支持设置频率惩罚系数，无 **frequency_penalty** 字段。
* 不支持设置存在惩罚系数，**presence_penalty** 字段。
* 不支持为单个请求生成多个返回，无 **n** 字段。

<span id="b867b8aa"></span>
# 常见问题

* [使用视觉理解模型时，报错InvalidParameter？](/docs/82379/1359411#effccb14)



部分模型支持处理PDF格式的文档，会通过视觉功能来理解整个文档的上下文。当传入PDF文档时，大模型会将文件分页处理成多图，然后分析解读对应的文本、图片等信息，并结合这些信息完成文档理解相关任务。
:::tip
方舟平台的新用户？获取 API Key 及 开通模型等准备工作，请参见 [快速入门](/docs/82379/1399008)。
:::
<span id="456f3bbb"></span>
# 支持模型
请参见[视觉理解能力](/docs/82379/1330310#ff5ef604)。
<span id="336a1278"></span>
# API 接口
[Responses API](https://www.volcengine.com/docs/82379/1569618)
<span id="2f59d3af"></span>
# 文档输入方式
支持文档传入方式如下：

* 本地文件上传：
    * [Files API 上传（推荐）](/docs/82379/1902647#d6d033c5)：直接传入本地文件路径，文件大小不能超过 512 MB。
    * [Base64 编码传入](/docs/82379/1902647#8160343d)：适用于文件体积较小的场景，文件小于 50 MB，请求体不能超过 64 MB。
* [文件 URL 传入](/docs/82379/1902647#69145d66)：适用于文件已存在公网可访问 URL 的场景，文件大小不能超过 50 MB。

<span id="9086f24c"></span>
## 本地文件上传
<span id="d6d033c5"></span>
### Files API 上传（推荐）
建议优先使用 Files API 上传本地文件，不仅可以支持最大 512MB 文件的处理，还可以避免请求时重新上传内容，减少预处理导致的时延，同时可在多次请求中重复使用，节省公网下载时延。其中文件预处理的原理，参见[附：文件预处理](/docs/82379/1902647#dbf9c161)。

> * 该方式上传的文件默认存储 7 天，存储有效期取值范围为1\-30天。
> * 如果需要实时获取分析内容，或者要规避复杂任务引发的客户端超时失败问题，可采用流式输出的方式，具体示例见[流式输出](/docs/82379/1902647#8e18e610)。

代码示例：

```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="kTCNfVfcIF"><RenderMd content={`1. 上传PDF文件获取File ID。
   \`\`\`Bash
   curl https://ark.cn-beijing.volces.com/api/v3/files \\
   -H "Authorization: Bearer $ARK_API_KEY" \\
   -F 'purpose=user_data' \\
   -F 'file=@/Users/doc/demo.pdf'
   \`\`\`
   
2. 在Responses API中引用File ID。
   \`\`\`Bash
   curl https://ark.cn-beijing.volces.com/api/v3/responses \\
   -H "Authorization: Bearer $ARK_API_KEY" \\
   -H 'Content-Type: application/json' \\
   -d '{
       "model": "doubao-seed-2-0-lite-260215",
       "input": [
           {
               "role": "user",
               "content": [
                   {
                       "type": "input_file",
                       "file_id": "file-20251018****"
                   },
                   {
                       "type": "input_text",
                       "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
                   }
               ]
           }
       ]
   }'
   \`\`\`
   
`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="Jcf3LXLG67"><RenderMd content={`\`\`\`Python
import asyncio
import os
from volcenginesdkarkruntime import AsyncArk
client = AsyncArk(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY')
)

async def main():
    # upload pdf file
    print("Upload pdf file")
    file = await client.files.create(
        # replace with your local pdf path
        file=open("/Users/doc/demo.pdf", "rb"),
        purpose="user_data"
    )
    print(f"File uploaded: {file.id}")

    # Wait for the file to finish processing
    await client.files.wait_for_processing(file.id)
    print(f"File processed: {file.id}")

    response = await client.responses.create(
        model="doubao-seed-2-0-lite-260215",
        input=[
            {"role": "user", "content": [
                {
                    "type": "input_file",
                    "file_id": file.id  # ref pdf file id
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
                }
            ]},
        ],
    )
    print(response)

if __name__ == "__main__":
    asyncio.run(main())
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="X0dgQffpRk"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "io"
    "os"
    "time"

    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/file"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() {
    client := arkruntime.NewClientWithApiKey(os.Getenv("ARK_API_KEY"))
    ctx := context.Background()

    fmt.Println("----- upload file data -----")
    data, err := os.Open("/Users/doc/demo.pdf")
    if err != nil {
        fmt.Printf("read file error: %v\\n", err)
        return
    }
    fileInfo, err := client.UploadFile(ctx, &file.UploadFileRequest{
        File:    data,
        Purpose: file.PurposeUserData,
    })

    if err != nil {
        fmt.Printf("upload file error: %v", err)
        return
    }

    // Wait for the file to finish processing
    for fileInfo.Status == file.StatusProcessing {
        fmt.Println("Waiting for file to be processed...")
        time.Sleep(2 * time.Second)
        fileInfo, err = client.RetrieveFile(ctx, fileInfo.ID) // update file info
        if err != nil {
            fmt.Printf("get file status error: %v", err)
            return
        }
    }
    fmt.Printf("File processing completed: %s, status: %s\\n", fileInfo.ID, fileInfo.Status)
    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_File{
                    File: &responses.ContentItemFile{
                        Type:   responses.ContentItemType_input_file,
                        FileId: volcengine.String(fileInfo.ID),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。",
                    },
                },
            },
        },
    }
    createResponsesReq := &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
        Caching: &responses.ResponsesCaching{Type: responses.CacheType_enabled.Enum()},
    }

    resp, err := client.CreateResponses(ctx, createResponsesReq)
    if err != nil {
        fmt.Printf("stream error: %v\\n", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="PTLvio8Z0B"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.files.FileMeta;
import com.volcengine.ark.runtime.model.files.UploadFileRequest;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemFile;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import java.io.File;
import java.util.concurrent.TimeUnit;

public class demo {

    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService service = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        System.out.println("===== Upload File Example=====");
        // upload a file for responses
        FileMeta fileMeta;
        fileMeta = service.uploadFile(
                UploadFileRequest.builder().
                        file(new File("/Users/doc/demo.pdf")) // replace with your file file path
                        .purpose("user_data")
                        .build());
        System.out.println("Uploaded file Meta: " + fileMeta);
        System.out.println("status:" + fileMeta.getStatus());

        try {
            while (fileMeta.getStatus().equals("processing")) {
                System.out.println("Waiting for file to be processed...");
                TimeUnit.SECONDS.sleep(2);
                fileMeta = service.retrieveFile(fileMeta.getId());
            }
        } catch (Exception e) {
            System.err.println("get file status error：" + e.getMessage());
        }
        System.out.println("Uploaded file Meta: " + fileMeta);

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemFile.InputContentItemFileBuilder.anInputContentItemFile().fileId(fileMeta.getId()).build())
                                        .addListItem(InputContentItemText.builder().text("按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。").build())
                                        .build()
                        ).build()
                ).build())
                .build();
        ResponseObject resp = service.createResponse(request);
        System.out.println(resp);
        service.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="RLUfYjefz0"><RenderMd content={`\`\`\`Python
import os
import time
from openai import OpenAI

api_key = os.getenv('ARK_API_KEY')

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

file = client.files.create(
    file=open("/Users/doc/demo.pdf", "rb"),
    purpose="user_data"
)
# Wait for the file to finish processing
while (file.status == "processing"):
    time.sleep(2)
    file = client.files.retrieve(file.id)
print(f"File processed: {file}")
    
response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [
                {
                    "type": "input_file",
                    "file_id": file.id,
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。",
                },
            ]
        }
    ]
)
print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

输出示例：
```JSON
{
    "text": [
        {
            "type": "heading",
            "content": "1 Introduction"
        },
        {
            "type": "paragraph",
            "content": "Diffusion models [3–5] learn to reverse a process that incrementally corrupts data with noise, effectively decomposing a complex distribution into a hierarchy of simplified representations. This coarse-to-fine generative approach has proven remarkably successful across a wide range of applications, including image and video synthesis [6] as well as solving complex challenges in natural sciences [7]."
        },
        ...
        {
            "type": "heading",
            "content": "3 Seed Diffusion"
        },
        {
            "type": "paragraph",
            "content": "As the first experimental model in our Seed Diffusion series, Seed Diffusion Preview is specifically focused on code generation, thus adopting the data pipeline (code/code-related data only) and processing methodology of the open-sourced Seed Coder project [20]. The architecture is a standard dense Transformer, and we intentionally omit complex components such as LongCoT reasoning in this initial version to first establish a strong and efficient performance baseline. This section introduces its key components and training strategies."
        }
    ]
}
```

<span id="8160343d"></span>
### Base64 编码传入
将本地文件转换为 Base64 编码字符串，然后提交给大模型。该方式适用于文档体积较小的情况，文件不能超过 50 MB，请求体不能超过 64 MB。
:::warning
将文档转换为Base64编码字符串，然后遵循`data:{mime_type};base64,{base64_data}`格式拼接，传入模型。

* `{mime_type}`：文件的媒体类型，需要与文件格式mime_type对应（`application/pdf`）。
* `{base64_data}`：文件经过Base64编码后的字符串。


:::
```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="TiteWcwXkT"><RenderMd content={`\`\`\`Bash
BASE64_FILE=$(base64 < demo.pdf) && curl https://ark.cn-beijing.volces.com/api/v3/responses \\
   -H "Content-Type: application/json"  \\
   -H "Authorization: Bearer $ARK_API_KEY"  \\
   -d @- <<EOF
   {
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
      {
        "role": "user",
        "content": [
          {
            "type": "input_file",
            "file_data": "data:application/pdf;base64,$BASE64_FILE",
            "filename": "demo.pdf" # When using file_data, the filename parameter is required.
          },
          {
            "type": "input_text",
            "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
          }
        ]
      }
    ]
  }
EOF
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="hlyWh5uyDC"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark
import base64

api_key = os.getenv('ARK_API_KEY')

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

# Convert local files to Base64-encoded strings.
def encode_file(file_path):
  with open(file_path, "rb") as read_file:
    return base64.b64encode(read_file.read()).decode('utf-8')
base64_file = encode_file("/Users/doc/demo.pdf")

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [
                {    
                    "type": "input_file",
                    "file_data": f"data:application/pdf;base64,{base64_file}",
                    "filename": "demo.pdf"
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
                }
            ]
        }
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="f6IPwILfEk"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "encoding/base64"
    "fmt"
    "os"

    "github.com/samber/lo"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() {
    // Convert local files to Base64-encoded strings.
    fileBytes, err := os.ReadFile("/Users/doc/demo.pdf")
    if err != nil {
        fmt.Printf("read file error: %v\\n", err)
        return
    }
    base64File := base64.StdEncoding.EncodeToString(fileBytes)
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_File{
                    File: &responses.ContentItemFile{
                        Type:     responses.ContentItemType_input_file,
                        FileData: lo.ToPtr(fmt.Sprintf("data:application/pdf;base64,%s", base64File)),
                        Filename: lo.ToPtr("demo.pdf"),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。",
                    },
                },
            },
        },
    }

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
    })
    if err != nil {
        fmt.Printf("response error: %v", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="f4mQcO0Mpk"><RenderMd content={`\`\`\`Java
package com.ark.sample;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemFile;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.io.IOException;

public class demo {
    private static String encodeFile(String filePath) throws IOException {
        byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
        return Base64.getEncoder().encodeToString(fileBytes);
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        // Convert local files to Base64-encoded strings.
        String base64Data = "";
        try {
            base64Data = "data:application/pdf;base64," + encodeFile("/Users/doc/demo.pdf");
        } catch (IOException e) {
            System.err.println("encode error: " + e.getMessage());
        }

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemFile.InputContentItemFileBuilder.anInputContentItemFile().fileData(base64Data).fileName("demo.pdf").build())
                                        .addListItem(InputContentItemText.builder().text("Provide the document’s text content by paragraph and output it in JSON format, including the paragraph type (type) and text content (content).").build())
                                        .build()
                        ).build()
                ).build())
                .build();
        ResponseObject resp = arkService.createResponse(request);
        System.out.println(resp);

        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="ly9pcki54P"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI
import base64

api_key = os.getenv('ARK_API_KEY')

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

# Convert local files to Base64-encoded strings.
def encode_file(file_path):
  with open(file_path, "rb") as read_file:
    return base64.b64encode(read_file.read()).decode('utf-8')
base64_file = encode_file("/Users/doc/demo.pdf")

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [
                {    
                    "type": "input_file",
                    "file_data": f"data:application/pdf;base64,{base64_file}",
                    "filename": "demo.pdf"
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
                }
            ]
        }
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="69145d66"></span>
## 文件 URL 传入
如果文档已存在公网可访问 URL，可以在 Responses API 请求中直接填入文档的公网 URL，文件不能超过50 MB。

```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="sTHpCeX5U8"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
-H "Authorization: Bearer $ARK_API_KEY" \\
-H 'Content-Type: application/json' \\
-d '{
    "model": "doubao-seed-2-0-lite-260215",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_file",
                    "file_url": "https://ark-project.tos-cn-beijing.volces.com/doc_pdf/demo.pdf"
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="GuJDVc53Wa"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

api_key = os.getenv('ARK_API_KEY')

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [

                {
                    "type": "input_file",
                    "file_url": "https://ark-project.tos-cn-beijing.volces.com/doc_pdf/demo.pdf"
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
                },
            ],
        }
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="gGZOAR7qk0"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"

    "github.com/samber/lo"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_File{
                    File: &responses.ContentItemFile{
                        Type:    responses.ContentItemType_input_file,
                        FileUrl: lo.ToPtr("https://ark-project.tos-cn-beijing.volces.com/doc_pdf/demo.pdf"),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。",
                    },
                },
            },
        },
    }

    resp, err := client.CreateResponses(ctx, &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
    })
    if err != nil {
        fmt.Printf("response error: %v", err)
        return
    }
    fmt.Println(resp)
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="SAZ4uYFGMB"><RenderMd content={`\`\`\`Java
package com.ark.example;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemFile;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;


public class demo {
    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        // The base URL for model invocation
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemFile.InputContentItemFileBuilder.anInputContentItemFile().fileUrl("https://ark-project.tos-cn-beijing.volces.com/doc_pdf/demo.pdf").build())
                                        .addListItem(InputContentItemText.builder().text("按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。").build())
                                        .build()
                        ).build()
                ).build())
                .build();
        ResponseObject resp = arkService.createResponse(request);
        System.out.println(resp);

        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="zCIOrOEdid"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

api_key = os.getenv('ARK_API_KEY')

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [

                {
                    "type": "input_file",
                    "file_url": "https://ark-project.tos-cn-beijing.volces.com/doc_pdf/demo.pdf"
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
                },
            ],
        }
    ]
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="8e18e610"></span>
# 流式输出
流式输出支持内容动态实时呈现，既能够缓解用户等待焦虑，又可以规避复杂任务因长时间推理引发的客户端超时失败问题，保障请求流程顺畅。

```mixin-react
return (<Tabs>
<Tabs.TabPane title="Python" key="iBM0yG2Q2s"><RenderMd content={`\`\`\`Python
import asyncio
import os
from volcenginesdkarkruntime import AsyncArk
from volcenginesdkarkruntime.types.responses.response_completed_event import ResponseCompletedEvent
from volcenginesdkarkruntime.types.responses.response_reasoning_summary_text_delta_event import ResponseReasoningSummaryTextDeltaEvent
from volcenginesdkarkruntime.types.responses.response_output_item_added_event import ResponseOutputItemAddedEvent
from volcenginesdkarkruntime.types.responses.response_text_delta_event import ResponseTextDeltaEvent
from volcenginesdkarkruntime.types.responses.response_text_done_event import ResponseTextDoneEvent

client = AsyncArk(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=os.getenv('ARK_API_KEY')
)

async def main():
    # upload pdf file
    print("Upload pdf file")
    file = await client.files.create(
        # replace with your local pdf path
        file=open("/Users/doc/demo.pdf", "rb"),
        purpose="user_data"
    )
    print(f"File uploaded: {file.id}")

    # Wait for the file to finish processing
    await client.files.wait_for_processing(file.id)
    print(f"File processed: {file.id}")

    stream = await client.responses.create(
        model="doubao-seed-2-0-lite-260215",
        input=[
            {"role": "user", "content": [
                {
                    "type": "input_file",
                    "file_id": file.id  # ref pdf file id
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。"
                }
            ]},
        ],
        caching={
            "type": "enabled",
        },
        store=True,
        stream=True
    )
    async for event in stream:
        if isinstance(event, ResponseReasoningSummaryTextDeltaEvent):
            print(event.delta, end="")
        if isinstance(event, ResponseOutputItemAddedEvent):
            print("\\noutPutItem " + event.type + " start:")
        if isinstance(event, ResponseTextDeltaEvent):
            print(event.delta,end="")
        if isinstance(event, ResponseTextDoneEvent):
            print("\\noutPutTextDone.")
        if isinstance(event, ResponseCompletedEvent):
            print("Response Completed. Usage = " + event.response.usage.model_dump_json())

if __name__ == "__main__":
    asyncio.run(main())
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="wOfOgkbYqu"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "io"
    "os"
    "time"

    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/file"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() {
    client := arkruntime.NewClientWithApiKey(os.Getenv("ARK_API_KEY"))
    ctx := context.Background()

    fmt.Println("----- upload file data -----")
    data, err := os.Open("/Users/doc/demo.pdf")
    if err != nil {
        fmt.Printf("read file error: %v\\n", err)
        return
    }
    fileInfo, err := client.UploadFile(ctx, &file.UploadFileRequest{
        File:    data,
        Purpose: file.PurposeUserData,
    })

    if err != nil {
        fmt.Printf("upload file error: %v", err)
        return
    }

    // Wait for the file to finish processing
    for fileInfo.Status == file.StatusProcessing {
        fmt.Println("Waiting for file to be processed...")
        time.Sleep(2 * time.Second)
        fileInfo, err = client.RetrieveFile(ctx, fileInfo.ID) // update file info
        if err != nil {
            fmt.Printf("get file status error: %v", err)
            return
        }
    }
    fmt.Printf("File processing completed: %s, status: %s\\n", fileInfo.ID, fileInfo.Status)
    inputMessage := &responses.ItemInputMessage{
        Role: responses.MessageRole_user,
        Content: []*responses.ContentItem{
            {
                Union: &responses.ContentItem_File{
                    File: &responses.ContentItemFile{
                        Type:   responses.ContentItemType_input_file,
                        FileId: volcengine.String(fileInfo.ID),
                    },
                },
            },
            {
                Union: &responses.ContentItem_Text{
                    Text: &responses.ContentItemText{
                        Type: responses.ContentItemType_input_text,
                        Text: "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。",
                    },
                },
            },
        },
    }
    createResponsesReq := &responses.ResponsesRequest{
        Model: "doubao-seed-2-0-lite-260215",
        Input: &responses.ResponsesInput{
            Union: &responses.ResponsesInput_ListValue{
                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                    Union: &responses.InputItem_InputMessage{
                        InputMessage: inputMessage,
                    },
                }}},
            },
        },
        Caching: &responses.ResponsesCaching{Type: responses.CacheType_enabled.Enum()},
    }

    resp, err := client.CreateResponsesStream(ctx, createResponsesReq)
    if err != nil {
        fmt.Printf("stream error: %v\\n", err)
        return
    }
    var responseId string
    for {
        event, err := resp.Recv()
        if err == io.EOF {
            break
        }
        if err != nil {
            fmt.Printf("stream error: %v\\n", err)
            return
        }
        handleEvent(event)
        if responseEvent := event.GetResponse(); responseEvent != nil {
            responseId = responseEvent.GetResponse().GetId()
            fmt.Printf("Response ID: %s", responseId)
        }
    }
}

func handleEvent(event *responses.Event) {
    switch event.GetEventType() {
    case responses.EventType_response_reasoning_summary_text_delta.String():
        print(event.GetReasoningText().GetDelta())
    case responses.EventType_response_reasoning_summary_text_done.String(): // aggregated reasoning text
        fmt.Printf("\\nAggregated reasoning text: %s\\n", event.GetReasoningText().GetText())
    case responses.EventType_response_output_text_delta.String():
        print(event.GetText().GetDelta())
    case responses.EventType_response_output_text_done.String(): // aggregated output text
        fmt.Printf("\\nAggregated output text: %s\\n", event.GetTextDone().GetText())
    default:
        return
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="PLYNGJrrzq"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.files.FileMeta;
import com.volcengine.ark.runtime.model.files.UploadFileRequest;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemFile;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;

import com.volcengine.ark.runtime.model.responses.event.functioncall.FunctionCallArgumentsDoneEvent;
import com.volcengine.ark.runtime.model.responses.event.outputitem.OutputItemAddedEvent;
import com.volcengine.ark.runtime.model.responses.event.outputitem.OutputItemDoneEvent;
import com.volcengine.ark.runtime.model.responses.event.outputtext.OutputTextDeltaEvent;
import com.volcengine.ark.runtime.model.responses.event.outputtext.OutputTextDoneEvent;
import com.volcengine.ark.runtime.model.responses.event.reasoningsummary.ReasoningSummaryTextDeltaEvent;
import com.volcengine.ark.runtime.model.responses.event.response.ResponseCompletedEvent;
import java.io.File;
import java.util.concurrent.TimeUnit;

public class demo {

    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService service = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        System.out.println("===== Upload File Example=====");
        // upload a file for responses
        FileMeta fileMeta;
        fileMeta = service.uploadFile(
                UploadFileRequest.builder().
                        file(new File("/Users/doc/demo.pdf")) // replace with your file file path
                        .purpose("user_data")
                        .build());
        System.out.println("Uploaded file Meta: " + fileMeta);
        System.out.println("status:" + fileMeta.getStatus());

        try {
            while (fileMeta.getStatus().equals("processing")) {
                System.out.println("Waiting for file to be processed...");
                TimeUnit.SECONDS.sleep(2);
                fileMeta = service.retrieveFile(fileMeta.getId());
            }
        } catch (Exception e) {
            System.err.println("get file status error：" + e.getMessage());
        }
        System.out.println("Uploaded file Meta: " + fileMeta);

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .stream(true)
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemFile.InputContentItemFileBuilder.anInputContentItemFile().fileId(fileMeta.getId()).build())
                                        .addListItem(InputContentItemText.builder().text("按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。").build())
                                        .build()
                        ).build()
                ).build())
                .build();

        service.streamResponse(request)
                .doOnError(Throwable::printStackTrace)
                .blockingForEach(event -> {
                    if (event instanceof ReasoningSummaryTextDeltaEvent) {
                        System.out.print(((ReasoningSummaryTextDeltaEvent) event).getDelta());
                    }
                    if (event instanceof OutputItemAddedEvent) {
                        System.out.println("\\nOutputItem " + (((OutputItemAddedEvent) event).getItem().getType()) + " Start: ");
                    }
                    if (event instanceof OutputTextDeltaEvent) {
                        System.out.print(((OutputTextDeltaEvent) event).getDelta());
                    }
                    if (event instanceof OutputTextDoneEvent) {
                        System.out.println("\\nOutputText End.");
                    }
                    if (event instanceof OutputItemDoneEvent) {
                        System.out.println("\\nOutputItem " + ((OutputItemDoneEvent) event).getItem().getType() + " End.");
                    }
                    if (event instanceof FunctionCallArgumentsDoneEvent) {
                        System.out.println("\\nFunctionCall Arguments: " + ((FunctionCallArgumentsDoneEvent) event).getArguments());
                    }
                    if (event instanceof ResponseCompletedEvent) {
                        System.out.println("\\nResponse Completed. Usage = " + ((ResponseCompletedEvent) event).getResponse().getUsage());
                    }
                });


        service.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="l98HcqDHQL"><RenderMd content={`\`\`\`Python
import os
import time
from openai import OpenAI

api_key = os.getenv('ARK_API_KEY')

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

file = client.files.create(
    file=open("/Users/doc/demo.pdf", "rb"),
    purpose="user_data"
)
# Wait for the file to finish processing
while (file.status == "processing"):
    time.sleep(2)
    file = client.files.retrieve(file.id)
print(f"File processed: {file}")
    
response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[
        {
            "role": "user",
            "content": [
                {
                    "type": "input_file",
                    "file_id": file.id,
                },
                {
                    "type": "input_text",
                    "text": "按段落给出文档中的文字内容，以JSON格式输出，包括段落类型（type）、文字内容（content）信息。",
                },
            ]
        }
    ],
    stream=True
)

for event in response:
    if event.type == "response.reasoning_summary_text.delta":
        print(event.delta, end="")
    if event.type == "response.output_item.added":
        print("\\noutPutItem " + event.type + " start:")
    if event.type == "response.output_text.delta":
        print(event.delta,end="")
    if event.type == "response.output_item.done":
        print("\\noutPutTextDone.")
    if event.type == "response.completed":
        print("\\nResponse Completed. Usage = " + event.response.usage.model_dump_json())
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="dbf9c161"></span>
# 附：文件预处理
对于PDF文件会分页来处理成多图，在预处理时不会对拆分的图片做分辨率缩放，以确保图片能够完整且清晰地保留PDF文件中的原始信息。在作为输入的时候，会根据模型**input.content.detail**参数的`auto`行为自动缩放。


在生成模型响应时，您可以使用方舟大模型内置工具、Function Calling 以及 MCP 等工具来扩展模型的功能。
这些功能使模型能够自行判断，并决策是否调用搜索网络资料、图片处理等内置工具或者您自己的自定义函数。

```mixin-react
return (<Tabs>
<Tabs.TabPane title="豆包助手" key="n7vdHBbR6n"><RenderMd content={`\`\`\`Bash
curl --location 'https://ark.cn-beijing.volces.com/api/v3/responses' \\
--header "Authorization: Bearer $ARK_API_KEY" \\
--header 'Content-Type: application/json' \\
--header 'ark-beta-doubao-app: true' \\
--data '{
    "model": "doubao-seed-2-0-lite-260215",
    "stream": true,
    "tools": [
        {
            "type": "doubao_app",
            "feature": {
                "ai_search": {
                    "type": "enabled",
                    "role_description": "你是科技领域助手，专业解答行业问题"
                },
            }
        }
    ],
    "input": [
        {
            "type": "message",
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "今天有什么AI领域热点新闻"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="联网内容插件" key="ltQyyfNjeR"><RenderMd content={`\`\`\`Bash
curl --location 'https://ark.cn-beijing.volces.com/api/v3/responses' \\
--header "Authorization: Bearer <ARK_API_KEY>" \\
--header 'Content-Type: application/json' \\
--data '{
    "model": "doubao-seed-2-0-lite-260215",
    "stream": true,
    "tools": [
        {"type": "web_search"}
    ],
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "今天有什么热点新闻"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="图像处理" key="niw99Yt0Sz"><RenderMd content={`\`\`\`Bash
curl --location 'https://ark.cn-beijing.volces.com/api/v3/responses' \\
--header 'Content-Type: application/json' \\
--header 'Authorization: Bearer <ARK_API_KEY>' \\
--header 'ark-beta-image-process: true' \\
--data '{
    "model": "doubao-seed-1-6-vision-250815",
    "stream": true,
    "tools": [
        {"type": "image_process"}
    ],
    "input": [
        {
            "type": "message",
            "role": "system",
            "content": [
                {
                    "type": "input_text",
                    "text": "数一数有多少颗草莓？"
                },
                {
                    "type": "input_image",
                    "image_url": "https://ark-project.tos-cn-beijing.volces.com/doc_image/image_process_2.jpg"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="私域知识库搜索" key="KVvUmBAbR9"><RenderMd content={`\`\`\`Bash
curl --location 'https://ark.cn-beijing.volces.com/api/v3/responses' \\
--header 'Content-Type: application/json' \\
--header 'Authorization: Bearer <ARK_API_KEY>' \\
--header 'ark-beta-knowledge-search: true' \\
--data '{
    "model": "doubao-seed-2-0-lite-260215",
    "stream": true,
    "thinking": {
        "type": "disabled"  # 关闭模型思考，直接调用知识库搜索
    },
    "tools": [
        {
            "type": "knowledge_search",
            "knowledge_resource_id": "<knowledge_resource_id>",  # 替换为实际知识库ID
            "limit": 2,  # 最多返回2条搜索结果
            "ranking_options": {
                "get_attachment_link": true  # 获取图片临时下载链接
            }
        }
    ],
    "input": [
        {
            "role": "user",
            "content": "机票选择页面中，如何选择该航班不同价格的机票？"  # 用户问题
        }
    ],
    "max_tool_calls": 1  # 仅调用1轮知识库搜索
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="MCP" key="y4St3HvOtH"><RenderMd content={`\`\`\`Bash
curl --location 'https://ark.cn-beijing.volces.com/api/v3/responses' \\
--header "Authorization: Bearer <ARK_API_KEY>" \\
--header 'Content-Type: application/json' \\
--header 'ark-beta-mcp: true' \\
--data '{
    "model": "doubao-seed-2-0-lite-260215",
    "stream": true,
    "tools": [
        {
            "type": "mcp",
            "server_label": "deepwiki",
            "server_url": "https://mcp.deepwiki.com/mcp",
            "require_approval": "never" 
        }
    ],
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "看一下volcengine/ai-app-lab这个repo的文档"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="函数调用" key="XiX65IoHOL"><RenderMd content={`\`\`\`Python
from volcenginesdkarkruntime import Ark
from volcenginesdkarkruntime.types.chat import ChatCompletion
import json
client = Ark()
messages = [
    {"role": "user", "content": "北京和上海今天的天气如何？"}
]
# 步骤1: 定义工具
tools = [{
  "type": "function",
  "function": {
    "name": "get_current_weather",
    "description": "获取指定地点的天气信息",
    "parameters": {
      "type": "object",
      "properties": {
        "location": {
          "type": "string",
          "description": "地点的位置信息，例如北京、上海"
        },
        "unit": {
          "type": "string",
          "enum": ["摄氏度", "华氏度"],
          "description": "温度单位"
        }
      },
      "required": ["location"]
    }
  }
}]
def get_current_weather(location: str, unit="摄氏度"):
    # 实际调用天气查询 API 的逻辑
    # 此处为示例，返回模拟的天气数据
    return f"{location}今天天气晴朗，温度 25 {unit}。"
while True:
    # 步骤2: 发起模型请求，由于模型在收到工具执行结果后仍然可能有工具调用意愿，因此需要多次请求
    completion: ChatCompletion = client.chat.completions.create(
    model="doubao-seed-2-0-lite-260215",
    messages=messages,
    tools=tools
    )
    resp_msg = completion.choices[0].message
    # 展示模型中间过程的回复内容
    print(resp_msg.content)
    if completion.choices[0].finish_reason != "tool_calls":
        # 模型最终总结，没有调用工具意愿
        break
    messages.append(completion.choices[0].message.model_dump())
    tool_calls = completion.choices[0].message.tool_calls
    for tool_call in tool_calls:
        tool_name = tool_call.function.name
        if tool_name == "get_current_weather":
            # 步骤 3：调用外部工具
            args = json.loads(tool_call.function.arguments)
            tool_result = get_current_weather(**args)
            # 步骤 4：回填工具结果，并获取模型总结回复
            messages.append(
                {"role": "tool", "content": tool_result, "tool_call_id": tool_call.id}
            )
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="24ee5c92"></span>
# 支持的工具
<span id="53fd9080"></span>
## 内置工具
当通过 Responses API [创建模型响应](https://www.volcengine.com/docs/82379/1569618)时，您可以通过在参数中配置`tools`字段来访问工具。每个工具中都有独特的配置要求，具体见如下教程：

* [豆包助手](/docs/82379/1978533)
  为您的应用快速集成与「豆包 App」同款的 AI 功能，例如日常沟通、深度沟通和联网搜索，快速获得安全、可靠且可控的优质 AI 体验。
* [Web Search（联网内容插件）](/docs/82379/1756990)
  支持获取实时公开网络信息（如新闻、商品、天气等），解决数据时效性、知识盲区、信息同步等核心问题，并且无需自行开发搜索引擎或维护数据资源。
* [图像处理 Image Process](/docs/82379/1798161)
  支持通过 Responses API 调用对输入图片执行画点、画线、旋转、缩放、框选/裁剪关键区域等基础操作，适用于需模型通过视觉处理提升图片理解的场景（如图文内容分析、物体定位标注、多轮视觉推理等）。工具通过模型自动判断图像处理逻辑，支持与自定义 Function 混合使用，且可处理多轮视觉输入（上一轮输出图片作为下一轮输入）。
* [私域知识库搜索 Knowledge Search](/docs/82379/1873396)
  支持通过 Responses API 调用直接获取企业私域知识库中的信息（如内部文档、产品手册、行业资料等），适用于需基于企业专属数据解答问题的场景（如内部培训问答、产品功能咨询、行业方案查询等）。工具通过模型自动判断是否需要调用私域知识库，支持与自定义 Function、MCP 等工具混合使用，目前仅支持旗舰版知识库。

<span id="fc9a8ccc"></span>
## 函数调用 Function Calling
您也可以在创建模型响应时，使用`tools`来定义自定义函数。模型通过调用自定义函数代码，来访问模型中无法直接使用的特定数据或功能。了解更多信息，请参见[Function Calling（函数调用）](/docs/82379/1262342)。
<span id="eda80765"></span>
## 云部署 MCP / Remote MCP
Responses API支持通过 Streamable HTTP 链接的 MCP 调用。适用于复杂任务（如多步数据查询 + 分析）场景，支持与自定义 Function、Web Search 工具混合使用。详情请参见[云部署 MCP / Remote MCP](/docs/82379/1827534)。


Web Search（联网内容插件）是一款基础联网搜索工具，能通过 Responses API 为您的大模型获取实时的公开网络信息（如新闻、商品、天气等）。使用此工具可以解决数据时效性、知识盲区、信息同步等核心问题，并且您无需自行开发搜索引擎或维护数据资源。

---


<span id="v7Us2ZbDGr"></span>
# **核心功能**

* **多轮自动搜索**：针对复杂问题，**模型会自动判断是否需要补充搜索**，您无需手动触发。
* **图文输入兼容**：支持 VLM 模型接收图文混合输入，模型结合图片识别结果判断是否发起关联搜索。
* **多工具混合调用**：可与自定义函数、MCP 等工具协同使用，模型会自动判断调用优先级与必要性。
* **响应模式灵活**：支持同步和流式两种响应模式。流式响应可实时返回思考、搜索、回答的全过程。


---


<span id="f5d73093"></span>
# 注意事项

* 命名冲突：自定义函数名称应避免使用`web_search`，否则模型将按内置优先级判断调用逻辑。
* 权限要求：需具备火山方舟平台基础访问权限，默认支持账号维度 5 QPS，有更高并发需求推荐接入[联网搜索](https://www.volcengine.com/product/SearchInfinity)产品。
* 计费说明：按联网内容插件实际使用次数计费，**是否触发搜索由模型判断**，一轮用户查询可能触发多个关键词搜索，可通过合理设置`max_keyword`参数限制，避免过多关键词导致调用次数增加，建议根据场景设置 1~10 个。关于`max_keyword`参数的具体使用方法和示例，请参见[创建模型响应](/docs/82379/1569618)。具体收费标准详见[联网内容插件产品计费](/docs/82379/1338550)。
* 用量查询：通过响应参数`usage.tool_usage`（总次数）和`usage.tool_usage_details`（明细）查看插件使用情况。关于这两个参数的详细信息，请参见[The response object](/docs/82379/1783703)。
* 错误信息：暂不支持`caching`参数，使用该参数会返回`400`错误。


---


<span id="b44cae6e"></span>
# **快速开始**
<span id="21052a40"></span>
## 开通联网内容插件

1. 在方舟控制台打开[服务组件库](https://console.volcengine.com/ark/region:ark+cn-beijing/components)页面。
2. 在**服务组件库**页面上找到联网内容插件，并在**操作**列中点击**开通。**

:::warning
是否触发搜索调用由模型判断，一轮搜索调用（若模型判定需要）可能会发起多个关键词同时搜索，会多次使用联网内容插件，您可以通过`max_keyword`参数来限制一轮搜索最大的关键词数量，进一步控制调用频次与成本。
:::
<span id="dc8effde"></span>
## 示例代码
:::tip
方舟平台的新用户？获取 API Key 及 开通模型等准备工作，请参见 [快速入门](/docs/82379/1399008)。

:::
```mixin-react
return (<Tabs>
<Tabs.TabPane title="cURL" key="ADqJArkJoo"><RenderMd content={`\`\`\`Bash
curl --location 'https://ark.cn-beijing.volces.com/api/v3/responses' \\
--header "Authorization: Bearer $ARK_API_KEY" \\
--header 'Content-Type: application/json' \\
--data '{
    "model": "doubao-seed-2-0-lite-260215",
    "stream": true,
    "tools": [
        {
            "type": "web_search",
            "max_keyword": 2
        }
    ],
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "今天有什么热点新闻？"
                }
            ]
        }
    ]
}'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python SDK" key="A2f4gTS5UE"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark

# To obtain the api key from your environment variables, see: https://www.volcengine.com/docs/82379/1820161
api_key = os.getenv('ARK_API_KEY')

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

tools = [{
    "type": "web_search",
    "max_keyword": 2,  
}]

# Create a conversation request
response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[{"role": "user", "content": "今天有什么热点新闻？"}],
    tools=tools,
)

print(response)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java SDK" key="eIIVawNar2"><RenderMd content={`\`\`\`Java
package com.ark.example;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.ark.runtime.model.responses.item.*;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.tool.ResponsesTool;
import com.volcengine.ark.runtime.model.responses.tool.ToolFunction;
import com.volcengine.ark.runtime.model.responses.tool.ToolWebSearch;
import com.volcengine.ark.runtime.service.ArkService;

import java.util.Arrays;
import java.util.List;

public class demo {
    public static ObjectMapper om = new ObjectMapper();

    public demo() throws JsonProcessingException {
    }

    public static List<ResponsesTool> buildTools() {
        ToolWebSearch t = ToolWebSearch.builder()
                .build();
        System.out.println(Arrays.asList(t));
        return Arrays.asList(t);
    }

    public static void main(String[] args) throws JsonProcessingException {
        String apiKey = System.getenv("ARK_API_KEY");


        // Create an ArkService instance
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();
        CreateResponsesRequest req = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .input(ResponsesInput.builder().addListItem(
                        ItemEasyMessage.builder().role(ResponsesConstants.MESSAGE_ROLE_USER).content(
                                MessageContent.builder()
                                        .addListItem(InputContentItemText.builder().text("今天有什么热点新闻？").build())
                                        .build()
                        ).build()
                ).build())
                .tools(buildTools())
                .build();
        ResponseObject resp = arkService.createResponse(req);
        System.out.println(resp);

        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go SDK" key="QGNOQZ0cwT"><RenderMd content={`\`\`\`Go
package main

import (
        "context"
        "fmt"
        "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
        "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
        "io"
        "os"
)

/**
 * Authentication
 * If you authorize your endpoint using an API key, you can set your api key to environment variable "ARK_API_KEY"
 * client := arkruntime.NewClientWithApiKey(os.Getenv("ARK_API_KEY"))
 * Note: If you use an API key, this API key will not be refreshed.
 * To prevent the API from expiring and failing after some time, choose an API key with no expiration date.
 */

func main() {
        stream()
}

func stream() {
        fmt.Println("stream")
        client := arkruntime.NewClientWithApiKey(os.Getenv("ARK_API_KEY"))
        ctx := context.Background()
        maxToolCalls := int64(1)

        inputMessage := &responses.ItemInputMessage{
                Role: responses.MessageRole_user,
                Content: []*responses.ContentItem{
                        {
                                Union: &responses.ContentItem_Text{
                                        Text: &responses.ContentItemText{
                                                Type: responses.ContentItemType_input_text,
                                                Text: "今天有什么热点新闻？",
                                        },
                                },
                        },
                },
        }
        createResponsesReq := &responses.ResponsesRequest{
                Model: "doubao-seed-2-0-lite-260215",
                Input: &responses.ResponsesInput{
                        Union: &responses.ResponsesInput_ListValue{
                                ListValue: &responses.InputItemList{ListValue: []*responses.InputItem{{
                                        Union: &responses.InputItem_InputMessage{
                                                InputMessage: inputMessage,
                                        },
                                }}},
                        },
                },
                Tools: []*responses.ResponsesTool{
                        {
                                Union: &responses.ResponsesTool_ToolWebSearch{
                                        ToolWebSearch: &responses.ToolWebSearch{
                                                Type: responses.ToolType_web_search,
                                        },
                                },
                        },
                },
                MaxToolCalls: &maxToolCalls,
        }

        resp, err := client.CreateResponsesStream(ctx, createResponsesReq)
        if err != nil {
                fmt.Printf("stream error: %v\\n", err)
                return
        }

        for {
                event, err := resp.Recv()
                if err == io.EOF {
                        break
                }
                if err != nil {
                        fmt.Printf("stream error: %v\\n", err)
                        return
                }
                handleEvent(event)
        }

        fmt.Println()
}

func handleEvent(event *responses.Event) {
        switch event.GetEventType() {
        case responses.EventType_response_reasoning_summary_text_delta.String():
                print(event.GetReasoningText().GetDelta())
        case responses.EventType_response_reasoning_summary_text_done.String(): // aggregated reasoning text
                fmt.Printf("\\naggregated reasoning text: %s\\n", event.GetReasoningText().GetText())
        case responses.EventType_response_output_text_delta.String():
                print(event.GetText().GetDelta())
        case responses.EventType_response_output_text_done.String(): // aggregated output text
                fmt.Printf("\\naggregated output text: %s\\n", event.GetText().GetText())
        case responses.EventType_response_output_item_done.String():
                if event.GetItem().GetItem().GetFunctionMcpApprovalRequest() != nil {
                        fmt.Printf("\\nmcp call: %s; arguments %s\\n", event.GetItem().GetItem().GetFunctionMcpApprovalRequest().GetName(), event.GetItem().GetItem().GetFunctionMcpApprovalRequest().GetArguments())
                }
        case responses.EventType_response_mcp_call_arguments_delta.String():
                fmt.Printf("\\nmcp call arguments: %s\\n", event.GetResponseMcpCallArgumentsDelta().GetDelta())
        case responses.EventType_response_mcp_call_completed.String():
                fmt.Printf("\\nmcp call completed.\\n")
        case responses.EventType_response_mcp_list_tools_in_progress.String():
                fmt.Printf("\\nlisting mcp tools.\\n")
        case responses.EventType_response_mcp_list_tools_completed.String():
                fmt.Printf("\\nDone listing mcp tools.\\n")
        default:
                return
        }
}
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


---


<span id="19b51cc2"></span>
# **参数说明**
详情请参见[创建 Responses 模型请求](https://www.volcengine.com/docs/82379/1569618)。

---


<span id="fab8ba6d"></span>
# **支持的模型**
参见[工具调用能力](/docs/82379/1330310#15a31773)。
> 关于 thinking 模式的设置，可以参考[开启/关闭深度思考](/docs/82379/1449737#fa3f44fa)。


---


<span id="55502143"></span>
# 模型输出
使用 Web Search 工具的模型回答，一般包含以下部分：

* `web_search_call`：包含搜索调用的 ID 以及执行的操作`web_search_call.action`。
* `message`：
    * `message.content[0].text`：文本结果。
    * `message.content[0].annotations`：引用网址的注释。


---


<span id="569e71b9"></span>
# 常用配置
本节介绍常见的使用场景及其字段设置方法，以帮助您更高效地运用联网内容插件。完整的参考代码见本节末尾。
<span id="b3a6340e"></span>
## 开启流式调用
在 response 中设置`stream=True`，即可开启流式调用，使响应以流式方式返回，从而更快地获取部分结果，同时可实时查看模型判断是否调用搜索的思考过程。
示例：
```Python
response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input=[  # 输入内容，包含系统提示和用户问题
        ...
    ],
    tools=[  # 使用工具及参数
        ...
    ],
    stream=True,  # 启用流式响应（实时返回结果，而非等待全部完成）
)
```

<span id="4a053e74"></span>
## 设置搜索来源
默认情况下，Web Search 工具会通过`search_engine`搜索全网内容。您也可以通过在 tools 中设置`sources`字段，来添加额外的内容源以优化搜索结果。是否触发搜索及使用哪些来源搜索，由模型判断。可用的附加搜索源包括：

* `"douyin"`：抖音百科
* `"moji"`：墨迹天气
* `"toutiao"`：头条图文

示例：
```Python
tools=[
    {
        "type": "web_search",  # 配置工具类型为联网内容插件
        "sources": ["douyin", "moji", "toutiao"],  # 附加搜索来源（抖音百科、墨迹天气、头条图文等平台）
    }
],
```

<span id="68c643c8"></span>
## 指定用户地理位置
您可以在 tools 中设置`user_location`字段，并提供用户的国家、地区和城市信息，以优化与地理位置相关的搜索结果。
示例：
```Python
tools=[
    {
        "type": "web_search",  # 配置工具类型为联网内容插件 
        "user_location": {  # 指定用户地理位置（用于优化搜索结果）
            "type": "approximate",  # 大致位置
            "country": "中国",
            "region": "浙江",
            "city": "杭州"
        }
    }
],
```

<span id="57cc853a"></span>
## 设置搜索限制
为平衡搜索效果与成本，您可以通过以下参数精细控制搜索行为，避免资源浪费或性能损耗：

* `tools.`**`max_keyword`**
    * 作用：限制单轮搜索中可使用的最大关键词数量。
    * 取值范围：`1`～`50`。
    * 示例：如果模型原本计划搜索三个关键词（例如“大模型最新进展”、“2025 年科技创新”），但将`max_keyword`设置为`1`，那么模型将仅使用第一个关键词进行搜索。关于`max_keyword`参数的具体使用方法和示例，请参见[创建模型响应](/docs/82379/1569618)。
* `tools.`**`limit`**
    * 作用：限制单轮搜索操作返回的最大结果条数。
    * 取值范围：`1`～`50`。
    * 默认值：`10`。
    * 说明：此参数会影响返回内容的规模和请求性能。单次搜索最多可返回 20 条结果，但单轮可能有多次搜索，默认召回 10 条。
* **`max_tool_calls`**
    * 作用：限制在一次完整的模型响应中可以执行工具调用的最大轮次。
    * 取值范围：`1` ～`10`。
    * 默认值：`3`。

示例：
```Python
tools = [{
    "type": "web_search",
    "max_keyword": 2, 
    "limit": 10, 
}],
max_tool_calls = 3,
```

<span id="MNcGnHGEfF"></span>
## 查询搜索用量
您可以在 API 返回的参数中查看联网内容插件的使用情况：

* `usage.tool_usage`：显示插件的总调用次数。
* `usage.tool_usage_details`：显示每个搜索源（例如`search_engine`、`toutiao`）的详细调用次数。

关于这两个参数的详细信息，请参见[The response object](/docs/82379/1783703)。
<span id="5638ef0e"></span>
## 设置系统提示词
系统提示词的设置对搜索请求的影响较大，建议进行优化以提升搜索的准确性与效率。以下两种系统提示词模板供您参考。
<span id="74d42962"></span>
### 模板一
```Python
# 定义系统提示词
system_prompt = """
你是AI个人助手，负责解答用户的各种问题。你的主要职责是：
1. **信息准确性守护者**：确保提供的信息准确无误。
2. **搜索成本优化师**：在信息准确性和搜索成本之间找到最佳平衡。
# 任务说明
## 1. 联网意图判断
当用户提出的问题涉及以下情况时，需使用 `web_search` 进行联网搜索：
- **时效性**：问题需要最新或实时的信息。
- **知识盲区**：问题超出当前知识范围，无法准确解答。
- **信息不足**：现有知识库无法提供完整或详细的解答。
## 2. 联网后回答
- 在回答中，优先使用已搜索到的资料。
- 回复结构应清晰，使用序号、分段等方式帮助用户理解。
## 3. 引用已搜索资料
- 当使用联网搜索的资料时，在正文中明确引用来源，引用格式为：  
`[1]  (URL地址)`。
## 4. 总结与参考资料
- 在回复的最后，列出所有已参考的资料。格式为：  
1. [资料标题](URL地址1)
2. [资料标题](URL地址2)
"""
```

<span id="dc10226a"></span>
### 模板二
```Python
# 定义系统提示词
system_prompt = """
# 角色
你是AI个人助手，负责解答用户的各种问题。你的主要职责是：
1. **信息准确性守护者**：确保提供的信息准确无误。
2. **回答更生动活泼**：请在模型的回复中多使用适当的 emoji 标签 🌟😊🎉
# 任务说明
## 1. 联网意图判断
当用户提出的问题涉及以下情况时，需使用 `web_search` 进行联网搜索：
- **时效性**：问题需要最新或实时的信息。
- **知识盲区**：问题超出当前知识范围，无法准确解答。
- **信息不足**：现有知识库无法提供完整或详细的解答。
## 2. 联网后回答
- 在回答中，优先使用已搜索到的资料。
- 回复结构应清晰，使用序号、分段等方式帮助用户理解。
## 3. 引用已搜索资料
- 当使用联网搜索的资料时，在正文中明确引用来源，引用格式为：  
`[1]  (URL地址)`。
## 4. 总结与参考资料
- 在回复的最后，列出所有已参考的资料。格式为：  
1. [资料标题](URL地址1)
2. [资料标题](URL地址2)
"""
```


---


<span id="e8909299"></span>
# 示例代码
<span id="b87a8642"></span>
## 示例 1：常用功能
请求：
```Python
import os
from volcenginesdkarkruntime import Ark

# To obtain the api key from your environment variables, see: https://www.volcengine.com/docs/82379/1820161
api_key = os.getenv('ARK_API_KEY')

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

tools = [{
    "type": "web_search",
    "max_keyword": 3,  
    "sources": ["douyin", "moji", "toutiao"],
}]

# Send request with streaming mode enabled
response = client.responses.create(
    model="doubao-seed-1-6-250615",
    input=[{"role": "user", "content": "搜索一下大模型领域最近有什么热门的科技新闻？火山方舟最近发布了什么新模型"}],
    tools=tools,
    stream=True
)

# Store the final event (for extracting usage info)
final_event = None

for event in response:
    print(event)  # Print all events
    # Capture the completion event
    if event.type == "response.completed":
        final_event = event


# Extract statistics of tool use
if final_event:
    tool_usage = final_event.response.usage.tool_usage
    tool_usage_details = final_event.response.usage.tool_usage_details
    print("\n===== 工具使用统计 =====")
    print(f"tool_usage: {tool_usage}")
    print(f"tool_usage_details: {tool_usage_details}")
```

返回：
```Python
...

===== 工具使用统计 =====
tool_usage: ToolUsage(web_search=2, mcp=None)
tool_usage_details: ToolUsageDetails(web_search={'search_engine': 2, 'toutiao': 2}, mcp=None)
```

<span id="dd0bb90d"></span>
## 示例 2：边想边搜
以下代码通过 OpenAI SDK 调用火山方舟 Web Search 工具，实现 “AI 思考 \- 联网搜索 \- 答案生成” 全链路自动化。**触发边想边搜的必需条件由系统提示词定义**：当用户问题满足**时效性（如近 3 年数据）、知识盲区（如小众领域信息）、信息不足（如细节缺失）**  三者之一时，自动触发工具补数据。通过**流式响应**实时输出思考、搜索、回答过程，保障信息可追溯、决策可感知。
```Python
import os
from openai import OpenAI
from datetime import datetime

def realize_think_while_search():

    # 1. 初始化 OpenAI 客户端
    client = OpenAI(
        base_url="https://ark.cn-beijing.volces.com/api/v3", 
        api_key=os.getenv("ARK_API_KEY")
    )

    # 2. 定义系统提示词（核心：规范“何时搜”“怎么搜”“怎么展示思考”）
    system_prompt = """
    你是AI个人助手，需实现“边想边搜边答”，核心规则如下：
    一、思考与搜索判断（必须实时输出思考过程）：
    1. 若问题涉及“时效性（如近3年数据）、知识盲区（如具体企业薪资）、信息不足”，必须调用web_search；
    2. 思考时需说明“是否需要搜索”“为什么搜”“搜索关键词是什么”。

    二、回答规则：
    1. 优先使用搜索到的资料，引用格式为`[1] (URL地址)`；
    2. 结构清晰（用序号、分段），多使用简单易懂的表述；
    3. 结尾需列出所有参考资料（格式：1. [资料标题](URL)）。
    """

    # 3. 构造 API 请求（触发思考-搜索-回答联动）
    response = client.responses.create(
        model="doubao-seed-1-6-250615",  
        input=[
            # 系统提示词（指导 AI 行为）
            {"role": "system", "content": [{"type": "input_text", "text": system_prompt}]},
            # 用户问题（可替换为任意需边想边搜的问题）
            {"role": "user", "content": [{"type": "input_text", "text": "世界500强企业在国内所在的城市，近三年的平均工资是多少？"}]}
        ],
        tools=[
            # 配置 Web Search 工具参数
            {
                "type": "web_search",
                "limit": 10,  # 最多返回 10 条搜索结果
                "sources": ["toutiao", "douyin", "moji"],  # 优先从头条内容、抖音百科，墨迹天气搜索
                "user_location": {  # 优化地域相关搜索结果（如国内城市）
                    "type": "approximate",
                    "country": "中国",
                    "region": "浙江",
                    "city": "杭州"
                }
            }
        ],
        stream=True,  # 启用流式响应（核心：实时获取思考、搜索、回答片段）
    )

    # 4. 处理流式响应（实时展示“思考-搜索-回答”过程）
    # 状态变量：避免重复打印标题
    thinking_started = False  # AI 思考过程是否已开始打印
    answering_started = False  # AI 回答是否已开始打印

    print("=== 边想边搜启动 ===")
    for chunk in response:  # 遍历每一个实时返回的片段（chunk）
        chunk_type = getattr(chunk, "type", "")  # 获取片段类型（思考/搜索/回答）

        # ① 处理 AI 思考过程（实时打印“为什么搜、搜什么”）
        if chunk_type == "response.reasoning_summary_text.delta":
            if not thinking_started:
                print(f"\n🤔 AI思考中 [{datetime.now().strftime('%H:%M:%S')}]:")
                thinking_started = True
            # 打印思考内容（delta 为实时增量文本）
            print(getattr(chunk, "delta", ""), end="", flush=True)

        # ② 处理搜索状态（开始/完成提示）
        elif "web_search_call" in chunk_type:
            if "in_progress" in chunk_type:
                print(f"\n\n🔍 开始搜索 [{datetime.now().strftime('%H:%M:%S')}]")
            elif "completed" in chunk_type:
                print(f"\n✅ 搜索完成 [{datetime.now().strftime('%H:%M:%S')}]")

        # ③ 处理搜索关键词（展示 AI 实际搜索的内容）
        elif (chunk_type == "response.output_item.done" 
              and hasattr(chunk, "item") 
              and str(getattr(chunk.item, "id", "")).startswith("ws_")):  # ws_为搜索结果标识
            if hasattr(chunk.item.action, "query"):
                search_keyword = chunk.item.action.query
                print(f"\n📝 本次搜索关键词：{search_keyword}")

        # ④ 处理最终回答（实时整合搜索结果并输出）
        elif chunk_type == "response.output_text.delta":
            if not answering_started:
                print(f"\n\n💬 AI回答 [{datetime.now().strftime('%H:%M:%S')}]:")
                print("-" * 50)
                answering_started = True
            # 打印回答内容（实时增量输出）
            print(getattr(chunk, "delta", ""), end="", flush=True)

    # 5. 流程结束
    print(f"\n\n=== 边想边搜完成 [{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] ===")

# 运行函数
if __name__ == "__main__":
    realize_think_while_search()
```

深度思考指模型在回答前，对问题进行分析及多步骤规划，再尝试解决问题。擅长处理编程、科学推理、智能体工作流等复杂及抽象场景。启用深度思考后，会在指定字段返回思维链内容，可基此观察和使用模型推导内容。
:::tip
方舟平台的新用户？获取 API Key 及 开通模型等准备工作，请参见 [快速入门](/docs/82379/1399008)。
:::
<span id="18cf565a"></span>
# 快速开始

<span aceTableMode="list" aceTableWidth="2,4,4"></span>
|输入 |思维链 |回答 |
|---|---|---|
|```Plain|```Plain|```Plain|\
|我要研究深度思考模型与非深度思考模型区别的课题，怎么体现我的专业性|用户现在要做深度思考模型和非深度思考模型区别的课题，需要体现专业性。首先得明确，专业性体现在哪里？|### **一、第一步：明确概念边界——避免泛化，精准定义**|\
|```|...|专业性的起点是**清晰的概念界定**，避免将“深度模型”等同于“深度思考模型”，也避免将“非深度模型”简化为“传统模型”。需基于学术共识和研究目标给出操作性定义：|\
| |要在“深度思考模型与非深度思考模型区别”的课题中体现专业性，核心在于**严谨的概念界定、系统的对比框架、科学的方法论支撑、以及深度的理论与实践结合**。以下是具体的实施路径，从研究框架到细节落地，帮你构建专业的研究体系：|...|\
| |```|通过以上路径，你的课题将从“表面对比”升级为“本质穿透”，充分体现专业性与研究深度。祝你研究顺利！|\
| | |```|\
| | | |

<span id="5538fa9e"></span>
## 示例代码

```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="SBEQzFNXEq"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $ARK_API_KEY" \\
  -d '\{
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        \{
            "role": "user",
            "content": "我要研究深度思考模型与非深度思考模型区别的课题，怎么体现我的专业性"
        \}
    ]
  \}'
\`\`\`


* 您可按需替换 Model ID。Model ID 查询见 [模型列表](/docs/82379/1330310)。
`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="Gox2kqEJ0a"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3",
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
    # Deep thinking takes longer; set a larger timeout, with 1,800 seconds or more recommended
    timeout=1800,
)

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        \{"role": "user", "content": "我要研究深度思考模型与非深度思考模型区别的课题，怎么体现我的专业性"\}
    ]
)
# When deep thinking is triggered, print the chain-of-thought content
if hasattr(completion.choices[0].message, 'reasoning_content'):
    print(completion.choices[0].message.reasoning_content)
print(completion.choices[0].message.content)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="YfRmnVB0lC"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "time"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() \{
    client := arkruntime.NewClientWithApiKey(
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
        // Deep thinking takes longer; set a larger timeout, with 1,800 seconds or more recommended
        arkruntime.WithTimeout(30*time.Minute),
    )
    ctx := context.Background()
    req := model.CreateChatCompletionRequest\{
        // Replace with Model ID
       Model: "doubao-seed-2-0-lite-260215",
        Messages: []*model.ChatCompletionMessage\{
            \{
                Role: model.ChatMessageRoleUser,
                Content: &model.ChatCompletionMessageContent\{
                    StringValue: volcengine.String("我要研究深度思考模型与非深度思考模型区别的课题，怎么体现我的专业性"),
                \},
            \},
        \},
    \}

    resp, err := client.CreateChatCompletion(ctx, req)
    if err != nil \{
        fmt.Printf("standard chat error: %v\\n", err)
        return
    \}
    // When deep thinking is triggered, print the chain-of-thought content
    if resp.Choices[0].Message.ReasoningContent != nil \{
        fmt.Println(*resp.Choices[0].Message.ReasoningContent)
    \}
    fmt.Println(*resp.Choices[0].Message.Content.StringValue)
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="D5TWG4MhqT"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionContentPart;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

public class ChatCompletionsExample \{
    public static void main(String[] args) \{
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        String apiKey = System.getenv("ARK_API_KEY");
        ArkService arkService = ArkService.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(30))// Deep thinking takes longer; set a larger timeout, with 1,800 seconds or more recommended
                .baseUrl("https://ark.cn-beijing.volces.com/api/v3")// The base URL for model invocation
                .build();
        List<ChatMessage> chatMessages = new ArrayList<>();
        ChatMessage userMessage = ChatMessage.builder()
                .role(ChatMessageRole.USER)
                .content("我要研究深度思考模型与非深度思考模型区别的课题，怎么体现我的专业性")
                .build();
        chatMessages.add(userMessage);
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                .model("doubao-seed-2-0-lite-260215")//Replace with Model ID
                .messages(chatMessages)
                .build();
        try \{
            arkService.createChatCompletion(chatCompletionRequest)
                    .getChoices()
                    .forEach(choice -> \{                    
                        if (choice.getMessage().getReasoningContent() != null) \{
                            System.out.println(choice.getMessage().getReasoningContent());
                        \}
                        System.out.println(choice.getMessage().getContent());
                    \});
        \} catch (Exception e) \{
            System.out.println(e.getMessage());
        \} finally \{
            // Shut down the service executor
            arkService.shutdownExecutor();
        \}
    \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="WfRQmG9e4i"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

client = OpenAI(
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.environ.get("ARK_API_KEY"), 
    base_url="https://ark.cn-beijing.volces.com/api/v3",
    # Deep thinking takes longer; set a larger timeout, with 1,800 seconds or more recommended
    timeout=1800,
    )
completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        \{"role": "user", "content": "我要研究深度思考模型与非深度思考模型区别的课题，怎么体现我的专业性"\}
    ]
)
# When deep thinking is triggered, print the chain-of-thought content
if hasattr(completion.choices[0].message, 'reasoning_content'):
    print(completion.choices[0].message.reasoning_content)
print(completion.choices[0].message.content)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="14b5c6db"></span>
# 模型及API
支持的模型：[深度思考能力](/docs/82379/1330310#898d064d)。
支持的API ：

* [Responses API](https://www.volcengine.com/docs/82379/1569618)：新推出的 API，简洁上下文管理，增强工具调用能力，缓存能力降低成本，新业务及用户推荐。
* [Chat API](https://www.volcengine.com/docs/82379/1494384)：使用广泛的 API，存量业务迁移成本低。

<span id="7cf8f2eb"></span>
# 基础使用
<span id="774e488d"></span>
## 多轮对话
组合使用系统消息、模型消息以及用户消息，可以实现多轮对话。当需要持续在一个主题内对话，可以将历史轮次的对话记录输入给模型。

<span aceTableMode="list" aceTableWidth="1,5,5"></span>
|传入方式 |手动管理上下文 |通过ID管理上下文 |
|---|---|---|
|使用示例 |```JSON|```JSON|\
| |...|...|\
| |    "model": "doubao-seed-2-0-lite-260215",|    "model": "doubao-seed-2-0-lite-260215",|\
| |    "messages":[|    "previous_response_id":"<id>",|\
| |        {"role": "user", "content": "Hi, tell a joke."},|    "input": "What is the punchline of this joke?"|\
| |        {"role": "assistant", "content": "Why did the math book look sad? Because it had too many problems! 😄"},|...|\
| |        {"role": "user", "content": "What's the punchline of this joke?"}|```|\
| |    ]| |\
| |...| |\
| |```| |\
| | | |
|API |[Chat API](https://www.volcengine.com/docs/82379/1494384) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |

> 在构建多轮对话的上下文时：
> * 模型版本在`251228`之前：剔除历史对话的 **reasoning_content** 字段，仅保留 `role` 和 `content`。方舟会尝试忽略该字段，但显式剔除能确保请求结构的正确性。
> * `doubao-seed-1.8`及后续模型：保留历史对话的 **reasoning_content** 字段，由模型自行判断是否将该字段加入到推理输入中。
    > 更多说明及完整示例请参见 [上下文管理](/docs/82379/2123288)。

<span id="4ad2b076"></span>
## 流式输出
随着大模型输出，动态输出内容，无需等待模型推理完毕，即可看到中间输出过程内容。

<span aceTableMode="list" aceTableWidth="1,2"></span>
|预览 |优势 |
|---|---|
|<video src="https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/0b0ed47ec1b94b20a4f4966aa80130e6~tplv-goo7wpa0wc-image.image" controls></video>|* **改善等待体验**：无需等待完整内容生成完毕，可立即处理过程内容。|\
| |* **实时过程反馈**：多轮交互场景，实时了解任务当前的处理阶段。|\
| |* **更高的容错性**：中途出错，也能获取到已生成内容，避免非流式输出失败无返回的情况。|\
| |* **简化超时管理**：保持客户端与服务端的连接状态，避免复杂任务耗时过长而连接超时。 |

通过配置 **stream** 为 `true`，来启用流式输出。
```JSON
...
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {"role": "user", "content": "深度思考模型与非深度思考模型区别"}
    ],
    "stream": true
 ...
```

> 完整示例及更多说明请参见 [流式输出](/docs/82379/2123275)。

<span id="fa3f44fa"></span>
## 开启/关闭深度思考
提供 **thinking** 字段控制是否关闭深度思考能力，实现“复杂任务深度推理，简单任务高效响应”的精细控制，获得成本、效率收益。

* 取值说明：
    * `enabled`：强制开启，强制开启深度思考能力。
    * `disabled`：强制关闭深度思考能力。
    * `auto`：模型自行判断是否进行深度思考。
* 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="knl3y8WHDI"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $ARK_API_KEY" \\
  -d '\{
    "model": "doubao-seed-2-0-lite-260215",
     "messages": [
         \{
             "role": "user",
             "content": [
                 \{
                     "type":"text",
                     "text":"我要研究深度思考模型与非深度思考模型区别的课题，体现出我的专业性"
                 \}
             ]
         \}
     ],
     "thinking":\{
         "type":"disabled"
     \}
\}'
\`\`\`


* **model**：请变更为实际调用的模型。
* **thinking.type**：字段可以取值范围。
   * \`disabled\`：强制关闭深度思考能力，模型不输出思维链内容。
   * \`enabled\`：强制开启深度思考能力，模型强制输出思维链内容。
   * \`auto\`：模型自行判断是否需要进行深度思考。
`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="V0UiqxURNS"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3",
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
    # Deep thinking takes longer; set a larger timeout, with 1,800 seconds or more recommended
    timeout=1800,
)

# 创建一个对话请求
completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        \{"role": "user", "content": "我要研究深度思考模型与非深度思考模型区别的课题，体现出我的专业性"\}
    ],
     thinking=\{
         "type": "disabled", # 不使用深度思考能力
         # "type": "enabled", # 使用深度思考能力
         # "type": "auto", # 模型自行判断是否使用深度思考能力
     \},
)

print(completion)
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="zinDmxpUxA"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "time"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() \{
    client := arkruntime.NewClientWithApiKey(
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
        //深度思考耗时更长，请设置更大的超时限制，推荐为30分钟及以上
        arkruntime.WithTimeout(30*time.Minute),
    )
    // 创建一个上下文，通常用于传递请求的上下文信息，如超时、取消等
    ctx := context.Background()
    // 构建聊天完成请求，设置请求的模型和消息内容
    req := model.CreateChatCompletionRequest\{
        // Replace with Model ID
       Model: "doubao-seed-2-0-lite-260215",
       Messages: []*model.ChatCompletionMessage\{
            \{
                // 消息的角色为用户
                Role: model.ChatMessageRoleUser,
                Content: &model.ChatCompletionMessageContent\{
                    StringValue: volcengine.String("我要研究深度思考模型与非深度思考模型区别的课题，怎么体现我的专业性"),
                \},
            \},
        \},
        Thinking: &model.Thinking\{
            Type: model.ThinkingTypeDisabled, // 关闭深度思考能力
            // Type: model.ThinkingTypeEnabled, //开启深度思考能力
            // Type: model.ThinkingTypeAuto, //模型自行判断是否使用深度思考能力
        \},
    \}


    // 发送聊天完成请求，并将结果存储在 resp 中，将可能出现的错误存储在 err 中
    resp, err := client.CreateChatCompletion(ctx, req)
    if err != nil \{
        // 若出现错误，打印错误信息并终止程序
        fmt.Printf("standard chat error: %v\\n", err)
        return
    \}
    // 检查是否触发深度思考，触发则打印思维链内容
    if resp.Choices[0].Message.ReasoningContent != nil \{
        fmt.Println(*resp.Choices[0].Message.ReasoningContent)
    \}
    // 打印聊天完成请求的响应结果
    fmt.Println(*resp.Choices[0].Message.Content.StringValue)
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="UcGltZmQiy"><RenderMd content={`\`\`\`Java
package com.ark.sample;

import com.volcengine.ark.runtime.model.completion.chat.*;
import com.volcengine.ark.runtime.service.ArkService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 这是一个示例类，展示了如何使用ArkService来完成聊天功能。
 */
public class ChatCompletionsExample \{
    public static void main(String[] args) \{
        // 从环境变量中获取API密钥
        String apiKey = System.getenv("ARK_API_KEY");
        // 创建ArkService实例
        ArkService arkService = ArkService.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(30))// 深度思考耗时更长，请设置更大的超时限制，推荐为30分钟及以上
                // The base URL for model invocation
                .baseUrl("https://ark.cn-beijing.volces.com/api/v3")
                .build();
        // 初始化消息列表
        List<ChatMessage> chatMessages = new ArrayList<>();
        // 创建用户消息
        ChatMessage userMessage = ChatMessage.builder()
                .role(ChatMessageRole.USER) // 设置消息角色为用户
                .content("我要研究深度思考模型与非深度思考模型区别的课题，怎么体现我的专业性") // 设置消息内容
                .build();
        // 将用户消息添加到消息列表
        chatMessages.add(userMessage);
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                .model("doubao-seed-2-0-lite-260215")//Replace with Model ID
                .messages(chatMessages) // 设置消息列表
                .thinking(new ChatCompletionRequest.ChatCompletionRequestThinking("disabled"))
                .build();
        // 发送聊天完成请求并打印响应
        try \{
            // 获取响应并打印每个选择的消息内容
            arkService.createChatCompletion(chatCompletionRequest)
                    .getChoices()
                    .forEach(choice -> \{                    
                        // 校验是否触发了深度思考，打印思维链内容
                        if (choice.getMessage().getReasoningContent() != null) \{
                            System.out.println("推理内容: " + choice.getMessage().getReasoningContent());
                        \} else \{
                            System.out.println("推理内容为空");
                        \}
                        // 打印消息内容
                        System.out.println("消息内容: " + choice.getMessage().getContent());
                    \});
        \} catch (Exception e) \{
            System.out.println("请求失败: " + e.getMessage());
        \} finally \{
            // 关闭服务执行器
            arkService.shutdownExecutor();
        \}
    \}
\}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="NhdGmVYPOm"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

client = OpenAI(
    # 从环境变量中读取方舟API Key
    api_key=os.environ.get("ARK_API_KEY"), 
    base_url="https://ark.cn-beijing.volces.com/api/v3",
    # 深度思考耗时更长，避免连接超时导致失败，请设置更大的超时限制，推荐为1800 秒及以上
    timeout=1800,
    )
completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        \{
            "role": "user",
            "content": "我要研究深度思考模型与非深度思考模型区别的课题，体现出我的专业性",
        \}
    ],
    extra_body=\{
        "thinking": \{
            "type": "disabled",  # 不使用深度思考能力
            # "type": "enabled", # 使用深度思考能力
            # "type": "auto", # 模型自行判断是否使用深度思考能力
        \}
    \},
)


print(completion)
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* 支持模型：
    * doubao\-seed\-2\-0\-lite\-260428：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-2\-0\-mini\-260428：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-2\-0\-pro\-260215：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-2\-0\-lite\-260215：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-2\-0\-mini\-260215：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-2\-0\-code\-preview\-260215：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-1\-8\-251228：支持 `enabled`（默认）、`disabled`。
    * glm\-4\-7\-251222：支持`enabled`（默认）、`disabled`。
    * doubao\-seed\-code\-preview\-251028：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-1\-6\-vision\-250815：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-1\-6\-lite\-251015：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-1\-6\-250615：支持 `enabled`（默认）、`disabled`、`auto`。
    * doubao\-seed\-1\-6\-251015：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-1\-6\-flash\-250828：支持 `enabled`（默认）、`disabled`。
    * doubao\-seed\-1\-6\-flash\-250615：支持 `enabled`（默认）、`disabled`。
    * deepseek\-v3\-2\-251201：支持 `enabled`、`disabled`（默认）。
    * deepseek\-v3\-1\-terminus：支持 `enabled`、`disabled`（默认）。
* 更多说明
    * Responses API 使用说明请参见 [控制深度思考](/docs/82379/1956279#19c1bd32)。
    * 深度思考会影响续写模式，详细信息请参见[续写模式](/docs/82379/1359497)。

&nbsp;
<span id="cfc7c5a8"></span>
## 设置最大输出长度
模型输出内容由**思维链（Chain of Thought, COT）**  和**最终回答（Answer）**  两部分组成。合理控制模型输出长度，平衡效果、速度、成本与稳定性。

<span aceTableMode="list" aceTableWidth="1,3,3"></span>
|传入方式 |手动管理上下文 |通过ID管理上下文 |
|---|---|---|
|API |[Chat API](https://www.volcengine.com/docs/82379/1494384) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |
|示例 |```JSON|```JSON|\
| |...|...|\
| |    "model": "doubao-seed-2-0-lite-260215",|    "model": "doubao-seed-2-0-lite-260215",|\
| |    "messages": [|    "previous_response_id":"<id>",|\
| |        {"role": "user", "content": "Hi, tell a joke."}|     "input": "Hi, tell a joke.",|\
| |    ],|     "max_output_tokens": 300|\
| |    "max_completion_tokens": 300|...|\
| |...|```|\
| |```| |\
| | | |

> 完整示例及更多说明请参见 [控制输出（回答+思维链）长度](/docs/82379/2123288#3cb3d444)。

<span id="fc5eac89"></span>
## 调节思考长度
提供字段 **reasoning_effort**（Chat API）、**reasoning.effort（** Responses API）调节思维链长度，平衡不同场景对效果、时延、成本的需求。取值如下：

* `minimal`：关闭思考，直接回答。
* `low`：轻量思考，侧重快速响应。
* `medium`（默认值）：均衡模式，兼顾速度与深度。
* `high`：深度分析，处理复杂问题。


<span aceTableMode="list" aceTableWidth="1,3,3"></span>
|API |[Chat API](https://www.volcengine.com/docs/82379/1494384) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |
|---|---|---|
|示例 |```JSON|```JSON|\
| |...|...|\
| |    "model": "doubao-seed-2-0-lite-260215",|    "model": "doubao-seed-2-0-lite-260215",|\
| |    "messages": [|    "input": [|\
| |        {"role": "user","content": "What are some common cruciferous plants?"}|        {"role": "user","content":"What are some common cruciferous plants?"}|\
| |    ],|    ],|\
| |    "reasoning_effort": "low"|    "reasoning":{"effort": "low"}|\
| |...|...|\
| |```|```|\
| | | |
|支持模型 |* doubao\-seed\-2\-0\-lite\-260428|* doubao\-seed\-2\-0\-lite\-260428|\
| |* doubao\-seed\-2\-0\-mini\-260428|* doubao\-seed\-2\-0\-mini\-260428|\
| |* doubao\-seed\-2\-0\-pro\-260215|* doubao\-seed\-2\-0\-pro\-260215|\
| |* doubao\-seed\-2\-0\-lite\-260215|* doubao\-seed\-2\-0\-lite\-260215|\
| |* doubao\-seed\-2\-0\-mini\-260215|* doubao\-seed\-2\-0\-mini\-260215|\
| |* doubao\-seed\-2\-0\-code\-preview\-260215|* doubao\-seed\-2\-0\-code\-preview\-260215|\
| |* doubao\-seed\-1\-8\-251228|* doubao\-seed\-1\-8\-251228|\
| |* doubao\-seed\-1\-6\-lite\-251015|* doubao\-seed\-1\-6\-lite\-251015|\
| |* doubao\-seed\-1\-6\-251015 |* doubao\-seed\-1\-6\-251015 |

完整示例及说明请参见 [控制思维链长度 [ 新增 ]](/docs/82379/2123288#480730d0)。
<span id="3cf44d66"></span>
## 输出思考内容摘要
<span id="fee31637"></span>
### 支持模型

* doubao\-seed\-2\-0\-lite\-260428

<span id="7be6563f"></span>
### 使用说明
默认会开启 thinking summary 能力，不会输出模型原始的思考内容，会返回模型思考内容摘要（**choices.message.reasoning_content**）、思考内容加密原文（**choices.message.encrypted_content**）。
:::tip
默认开启 thinking summary 能力后，可能会有较高的包间延迟，请调大请求超时时间（**timeout**），并做好兼容适配。
:::
字段说明：

* **reasoning_effort**：仅作用于模型的原始思考内容，不适用于思考摘要。
* **usage.completion_tokens_details.reasoning_tokens**：为原始思考内容的 tokens，计费仍然按原始思考内容 token 计算。

<span id="b9e7f4ab"></span>
### 回传思考内容
在工具调用场景，需要回传思考内容，回传注意事项如下。回传思考内容示例参见[回传思考内容加密原文](/docs/82379/1449737#8cfd447b)。

* 回传 **encrypted_content**、**reasoning_content** 字段： **encrypted_content** 字段优先级高，会忽略 **reasoning_content** 中的内容。其中 **encrypted_content** 内容必须为有效内容，如果被篡改则无法还原。
* 仅回传 **reasoning_content** 字段：使用思考内容摘要参与模型推理。在多轮工具调用场景（如 agent 场景），如果没有回传 **encrypted_content** 字段，将导致模型推理效果下降。
* 未回传思考内容相关字段：不会报错。

<span id="3e8661f7"></span>
## 工具调用
doubao\-seed\-1.8 之前的模型在工具调用场景中开启深度思考后，会直接丢弃思维链内容。doubao\-seed\-1.8 及部分模型为给出更详尽准确的回答，将不会直接丢弃思维链内容，思维链内容可能参与后续轮次推理，输入 tokens 会增加，具体参见[工作原理](/docs/82379/1449737#e1e56b26)。
:::tip
推荐在 Responses API 中使用 previous_response_id，平台自动保存历史对话的上下文，并在多轮交互中回传给推理服务。
:::
<span id="8cfd447b"></span>
### 回传思考内容加密原文
Seed 系列 lite 模型在260428及后续版本中默认会开启 thinking summary 能力，不会输出模型原始的思考内容。以下是在工具调用场景，回传思考内容加密原文的示例。其中使用 Responses API 推荐使用 previous_responses_id 自动获取原始思考内容并回传给模型参与推理。

<span aceTableMode="list" aceTableWidth="1,5,5"></span>
|API |[Chat API](https://www.volcengine.com/docs/82379/1494384) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |
|---|---|---|
|支持模型及说明 |* [支持模型](/docs/82379/1449737#fee31637)|* [支持模型](/docs/82379/1956279#7e7354e3)|\
| |* [回传思考内容](/docs/82379/1449737#b9e7f4ab) |* [回传思考内容](/docs/82379/1956279#cde1bf53) |
|示例 |**第一轮请求：触发工具调用**|**第一轮请求：触发工具调用**|\
| |```Bash|```Bash|\
| |curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \|curl https://ark.cn-beijing.volces.com/api/v3/responses \|\
| |  -H "Content-Type: application/json" \|    -H "Authorization: Bearer $ARK_API_KEY" \|\
| |  -H "Authorization: Bearer $ARK_API_KEY" \|    -H "Content-Type: application/json" \|\
| |  -d '{|    -d '{|\
| |    "model": "doubao-seed-2-0-lite-260215",|        "model": "doubao-seed-2-0-lite-260215",|\
| |    "messages": [|        "input": [|\
| |        {|            {|\
| |            "role": "system",|                "role": "system",|\
| |            "content": "你是人工智能助手。"|                "content": "你是人工智能助手."|\
| |        },|            },|\
| |        {|            {|\
| |            "role": "user",|                "role": "user",|\
| |            "content": "今天北京天气怎么样"|                "content": "今天北京天气怎么样"|\
| |        }|            }|\
| |    ],|        ],|\
| |    "thinking":{"type": "enabled"},|        "thinking":{"type": "enabled"},|\
| |    "tools": [|        "include":["reasoning.encrypted_content"],|\
| |        {|        "tools": [|\
| |            "type": "function",|            {|\
| |            "function": {|                "type": "function",|\
| |                "name": "get_weather",|                "name": "get_weather",|\
| |                "description": "天气查询",|                "description": "天气查询",|\
| |                "parameters": {|                "parameters": {|\
| |                    "properties": {|                    "type": "object",|\
| |                        "location": {|                    "properties": {|\
| |                            "description": "地点的位置信息，例如北京、上海。",|                        "location": {|\
| |                            "type": "string"|                            "type": "string",|\
| |                        }|                            "description": "地点的位置信息，例如北京、上海。"|\
| |                    },|                        }|\
| |                    "required": [|                    },|\
| |                        "location"|                    "required": ["location"]|\
| |                    ],|                }|\
| |                    "type": "object"|            }|\
| |                }|        ]|\
| |            }|    }'|\
| |        }|```|\
| |    ]||\
| |  }'|**第一轮响应：返回工具调用指令**|\
| |```|模型返回信息包含`id`、`call_id`、`arguments`等关键字段。|\
| ||```Bash|\
| |**第一轮响应：返回工具调用指令**|{|\
| |模型会返回`encrypted_content`、`reasoning_content`、`tool_calls`等关键字段。|    "created_at": 1766126702,|\
| |```Bash|    "id": "resp_0217661267019147d8950efa0e2f7c9d9cc7a1cc971272cf4548c",|\
| |{|    "max_output_tokens": 32768,|\
| |    "choices": [|    "model": "doubao-seed-1-8-251228",|\
| |        {|    "object": "response",|\
| |            "finish_reason": "tool_calls",|    "output": [|\
| |            "index": 0,|        {|\
| |            "logprobs": null,|            "id": "rs_02176612670248500000000000000000000ffffac154e10754f5c",|\
| |            "message": {|            "type": "reasoning",|\
| |                "content": "",|            "summary": [|\
| |                "reasoning_content": "北京天气查询将由我调用相关工具完成。\n",|                {|\
| |                "encrypted_content": "djF+2EICEj3ryfEfSUdR/SmS8OeEH4znOYftL4SWDXR8uxROjx11W7rRCj5ArLwzsm7rFsO4frOdLm2p3/yWz/r0TMqrjHiaTTvRMNdV6sLdETySlb3PDgY1W+zuYuETiq3bQuxga5jKx+GpfvlDJMfJfzq/G1kDp6ryurs0rKAFIziyc4mfFSh2CzDKNcAcp5Fi5R7M2QrSYmIUJjnoB48IVUCzu4xn7bT05qheVnGO9fbs15gYK3zINUvVsp51Oq72U/ksrPZFVs2BTgNRwjmxnFNn7A==",|                    "type": "summary_text",|\
| |                "role": "assistant",|                    "text": "用户查询北京今日天气，我将调用天气工具获取相关信息。"|\
| |                "tool_calls": [|                }|\
| |                    {|            ],|\
| |                        "function": {|            "status": "completed",|\
| |                            "arguments": " {\"location\": \"北京\"}",|            "encrypted_content": "djEqHS8w8bISWDUfivQXaeCUc8ms2JcjMBO5KQMRqKhTUdYlhbFebcndgVlFJxYUOSOAXm7gNsJdTRtp47iHpps76Rp37ipRrkEHMqIIt+KyKmN/rH9tzL+7ZLI9W4LGYMOv/27Rfqp2NW5vxiF7zkI1xgxxJFp6Vo8PNQpR68T4F7bG4PekickNR3U+EFM6hBKkhnJqxqCrjubi0o/8C35IoDF998+G6hokaDhOb6EqJ5fXaSZvtQJaK4DBh4HIciMFnRqzts/xlacBHsWCWLcxUASrvj0vYIs9a+ZN9BxkLjrBy/nEOOEcmID/I2NukCDEFa7zxlOXLvdZHuslP5cvyno="|\
| |                            "name": "get_weather"|        },|\
| |                        },|        {|\
| |                        "id": "call_wiezxeyae8jzxl3jx8nhfgb5",|            "arguments": " {\"location\": \"北京\"}",|\
| |                        "type": "function"|            "call_id": "call_t885uulopdd499rn0pioze7l",|\
| |                    }|            "name": "get_weather",|\
| |                ]|            "type": "function_call",|\
| |            }|            "id": "fc_02176612670345400000000000000000000ffffac154e10a6753e",|\
| |        }|            "status": "completed"|\
| |    ],|        }|\
| |    ...|    ],|\
| | }|    ....|\
| |```| }|\
| ||```|\
| |**第二轮请求：回传完整上下文并生成最终响应**||\
| |下面示例是在第一轮请求的基础上，回传思考内容加密原文（**encrypted_content**）和思考内容摘要（**reasoning_content**）、工具调用结果，模型生成自然语言回答。|**第二轮请求：回传结果并生成最终响应**|\
| |```Bash|传入上一轮 response_id、工具调用结果等信息，模型生成自然语言回答。|\
| |curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \|```Bash|\
| |  -H "Content-Type: application/json" \|curl https://ark.cn-beijing.volces.com/api/v3/responses \|\
| |  -H "Authorization: Bearer $ARK_API_KEY" \|    -H "Authorization: Bearer $ARK_API_KEY" \|\
| |  -d '{|    -H "Content-Type: application/json" \|\
| |    "model": "doubao-seed-2-0-lite-260215",|    -d '{|\
| |    "messages": [|        "model": "doubao-seed-2-0-lite-260215",|\
| |        {|        "input": [|\
| |            "role": "system",|            {|\
| |            "content": "你是人工智能助手。"|                "type": "function_call_output",|\
| |        },|                "call_id": "call_t885uulopdd499rn0pioze7l",|\
| |        {|                "output": "5度"|\
| |            "role": "user",|            }|\
| |            "content": "今天北京天气怎么样"|        ],|\
| |        },|        "previous_response_id": "resp_0217661267019147d8950efa0e2f7c9d9cc7a1cc971272cf4548c",|\
| |        {|        "thinking":{"type": "enabled"},|\
| |            "reasoning_content": "北京天气查询将由我调用相关工具完成。\n",|        "tools": [|\
| |            "encrypted_content": "djF+2EICEj3ryfEfSUdR/SmS8OeEH4znOYftL4SWDXR8uxROjx11W7rRCj5ArLwzsm7rFsO4frOdLm2p3/yWz/r0TMqrjHiaTTvRMNdV6sLdETySlb3PDgY1W+zuYuETiq3bQuxga5jKx+GpfvlDJMfJfzq/G1kDp6ryurs0rKAFIziyc4mfFSh2CzDKNcAcp5Fi5R7M2QrSYmIUJjnoB48IVUCzu4xn7bT05qheVnGO9fbs15gYK3zINUvVsp51Oq72U/ksrPZFVs2BTgNRwjmxnFNn7A==",|            {|\
| |            "role": "assistant",|                "type": "function",|\
| |            "tool_calls": [|                "name": "get_weather",|\
| |                {|                "description": "天气查询",|\
| |                    "function": {|                "parameters": {|\
| |                        "arguments": " {\"location\": \"北京\"}",|                    "type": "object",|\
| |                        "name": "get_weather"|                    "properties": {|\
| |                    },|                        "location": {|\
| |                    "id": "call_wiezxeyae8jzxl3jx8nhfgb5",|                            "type": "string",|\
| |                    "type": "function"|                            "description": "地点的位置信息，例如北京、上海。"|\
| |                }|                        }|\
| |            ]|                    },|\
| |        },|                    "required": ["location"]|\
| |        {|                }|\
| |            "role": "tool",|            }|\
| |            "tool_call_id":"call_wiezxeyae8jzxl3jx8nhfgb5",|        ]|\
| |            "content": "5度"|    }'|\
| |        }|```|\
| |    ],| |\
| |    "thinking":{"type": "enabled"},| |\
| |    "tools": [| |\
| |        {| |\
| |            "type": "function",| |\
| |            "function": {| |\
| |                "name": "get_weather",| |\
| |                "description": "天气查询",| |\
| |                "parameters": {| |\
| |                    "properties": {| |\
| |                        "location": {| |\
| |                            "description": "地点的位置信息，例如北京、上海。",| |\
| |                            "type": "string"| |\
| |                        }| |\
| |                    },| |\
| |                    "required": [| |\
| |                        "location"| |\
| |                    ],| |\
| |                    "type": "object"| |\
| |                }| |\
| |            }| |\
| |        }| |\
| |    ]| |\
| |  }'| |\
| |```| |\
| | | |

<span id="120ee16f"></span>
### 回传原始思考内容
部分模型开启深度思考后，默认输出模型原始的思考内容。以下是在工具调用场景，回传原始思考内容的示例。

<span aceTableMode="list" aceTableWidth="1,5,5"></span>
|API |[Chat API](https://www.volcengine.com/docs/82379/1494384) |[Responses API](https://www.volcengine.com/docs/82379/1569618) |
|---|---|---|
|支持模型 |* doubao\-seed\-2\-0\-lite\-260428|* doubao\-seed\-2\-0\-lite\-260428|\
| |* doubao\-seed\-2\-0\-mini\-260428|* doubao\-seed\-2\-0\-mini\-260428|\
| |* doubao\-seed\-2\-0\-pro\-260215|* doubao\-seed\-2\-0\-pro\-260215|\
| |* doubao\-seed\-2\-0\-lite\-260215|* doubao\-seed\-2\-0\-lite\-260215|\
| |* doubao\-seed\-2\-0\-mini\-260215|* doubao\-seed\-2\-0\-mini\-260215|\
| |* doubao\-seed\-2\-0\-code\-preview\-260215|* doubao\-seed\-2\-0\-code\-preview\-260215|\
| |* doubao\-seed\-1\-8\-251228|* doubao\-seed\-1\-8\-251228|\
| |* deepseek\-v3\-2\-251201 |* deepseek\-v3\-2\-251201 |
|示例 |**第一轮请求：触发工具调用**|**第一轮请求：触发工具调用**|\
| |```Bash|```Bash|\
| |curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \|curl https://ark.cn-beijing.volces.com/api/v3/responses \|\
| |  -H "Content-Type: application/json" \|    -H "Authorization: Bearer $ARK_API_KEY" \|\
| |  -H "Authorization: Bearer $ARK_API_KEY" \|    -H "Content-Type: application/json" \|\
| |  -d '{|    -d '{|\
| |    "model": "doubao-seed-2-0-lite-260215",|        "model": "doubao-seed-2-0-lite-260215",|\
| |    "messages": [|        "input": [|\
| |        {|            {|\
| |            "role": "system",|                "role": "system",|\
| |            "content": "你是人工智能助手。"|                "content": "你是人工智能助手."|\
| |        },|            },|\
| |        {|            {|\
| |            "role": "user",|                "role": "user",|\
| |            "content": "今天北京天气怎么样"|                "content": "今天北京天气怎么样"|\
| |        }|            }|\
| |    ],|        ],|\
| |    "thinking":{"type": "enabled"},|        "thinking":{"type": "enabled"},|\
| |    "tools": [|        "tools": [|\
| |        {|            {|\
| |            "type": "function",|                "type": "function",|\
| |            "function": {|                "name": "get_weather",|\
| |                "name": "get_weather",|                "description": "天气查询",|\
| |                "description": "天气查询",|                "parameters": {|\
| |                "parameters": {|                    "type": "object",|\
| |                    "properties": {|                    "properties": {|\
| |                        "location": {|                        "location": {|\
| |                            "description": "地点的位置信息，例如北京、上海。",|                            "type": "string",|\
| |                            "type": "string"|                            "description": "地点的位置信息，例如北京、上海。",|\
| |                        }|                        }|\
| |                    },|                    },|\
| |                    "required": [|                    "required": ["location"]|\
| |                        "location"|                }|\
| |                    ],|            }|\
| |                    "type": "object"|        ]|\
| |                }|    }'|\
| |            }|```|\
| |        }||\
| |    ]|**第一轮响应：返回工具调用指令**|\
| |  }'|模型返回信息包含`id`、`call_id`、`arguments`等关键字段。|\
| |```|```Bash|\
| ||{|\
| |**第一轮响应：返回工具调用指令**|    "created_at": 1766126702,|\
| |模型会返回`reasoning_content`、`tool_calls`等关键字段。|    "id": "resp_0217661267019147d8950efa0e2f7c9d9cc7a1cc971272cf4548c",|\
| |```JSON|    "max_output_tokens": 32768,|\
| |{|    "model": "doubao-seed-1-8-251228",|\
| |    "choices": [|    "object": "response",|\
| |        {|    "output": [|\
| |            "finish_reason": "tool_calls",|        {|\
| |            "index": 0,|            "id": "rs_02176612670248500000000000000000000ffffac154e10754f5c",|\
| |            "logprobs": null,|            "type": "reasoning",|\
| |            "message": {|            "summary": [|\
| |                "content": "",|                {|\
| |                "reasoning_content": "用户想查询今天北京的天气，这正好符合get_weather工具的功能，需要传入location参数为北京。所以我要调用这个工具来获取天气信息。",|                    "type": "summary_text",|\
| |                "role": "assistant",|                    "text": "用户问今天北京的天气怎么样，我需要调用get_weather工具，参数location是北京。按照格式要求来写函数调用。"|\
| |                "tool_calls": [|                }|\
| |                    {|            ],|\
| |                        "function": {|            "status": "completed"|\
| |                            "arguments": " {\"location\": \"北京\"}",|        },|\
| |                            "name": "get_weather"|        {|\
| |                        },|            "arguments": " {\"location\": \"北京\"}",|\
| |                        "id": "call_wiezxeyae8jzxl3jx8nhfgb5",|            "call_id": "call_t885uulopdd499rn0pioze7l",|\
| |                        "type": "function"|            "name": "get_weather",|\
| |                    }|            "type": "function_call",|\
| |                ]|            "id": "fc_02176612670345400000000000000000000ffffac154e10a6753e",|\
| |            }|            "status": "completed"|\
| |        }|        }|\
| |    ],|    ],|\
| |    ...|    ....|\
| | }| }|\
| |```|```|\
| |||\
| |**第二轮请求：回传完整上下文并生成最终响应**|**第二轮请求：回传结果并生成最终响应**|\
| |在第一轮请求的基础上，还需要回传思维链信息、工具调用结果，模型生成自然语言回答。|传入上一轮 response_id、工具调用结果等信息，模型生成自然语言回答。|\
| |```Bash|```Bash|\
| |curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \|curl https://ark.cn-beijing.volces.com/api/v3/responses \|\
| |  -H "Content-Type: application/json" \|    -H "Authorization: Bearer $ARK_API_KEY" \|\
| |  -H "Authorization: Bearer $ARK_API_KEY" \|    -H "Content-Type: application/json" \|\
| |  -d '{|    -d '{|\
| |    "model": "doubao-seed-2-0-lite-260215",|        "model": "doubao-seed-2-0-lite-260215",|\
| |    "messages": [|        "input": [|\
| |        {|            {|\
| |            "role": "system",|                "type": "function_call_output",|\
| |            "content": "你是人工智能助手。"|                "call_id": "call_t885uulopdd499rn0pioze7l",|\
| |        },|                "output": "5度"|\
| |        {|            }|\
| |            "role": "user",|        ],|\
| |            "content": "今天北京天气怎么样"|        "previous_response_id": "resp_0217661267019147d8950efa0e2f7c9d9cc7a1cc971272cf4548c",|\
| |        },|        "thinking":{"type": "enabled"},|\
| |        {|        "tools": [|\
| |            "reasoning_content": "用户想查询今天北京的天气，这正好符合get_weather工具的功能，需要传入location参数为北京。所以我要调用这个工具来获取天气信息。",|            {|\
| |            "role": "assistant",|                "type": "function",|\
| |            "tool_calls": [|                "name": "get_weather",|\
| |                {|                "description": "天气查询",|\
| |                    "function": {|                "parameters": {|\
| |                        "arguments": " {\"location\": \"北京\"}",|                    "type": "object",|\
| |                        "name": "get_weather"|                    "properties": {|\
| |                    },|                        "location": {|\
| |                    "id": "call_wiezxeyae8jzxl3jx8nhfgb5",|                            "type": "string",|\
| |                    "type": "function"|                            "description": "地点的位置信息，例如北京、上海。"|\
| |                }|                        }|\
| |            ]|                    },|\
| |        },|                    "required": ["location"]|\
| |        {|                }|\
| |            "role": "tool",|            }|\
| |            "tool_call_id":"call_wiezxeyae8jzxl3jx8nhfgb5",|        ]|\
| |            "content": "5度"|    }'|\
| |        }|```|\
| |    ],| |\
| |    "thinking":{"type": "enabled"},| |\
| |    "tools": [| |\
| |        {| |\
| |            "type": "function",| |\
| |            "function": {| |\
| |                "name": "get_weather",| |\
| |                "description": "天气查询",| |\
| |                "parameters": {| |\
| |                    "properties": {| |\
| |                        "location": {| |\
| |                            "description": "地点的位置信息，例如北京、上海。",| |\
| |                            "type": "string"| |\
| |                        }| |\
| |                    },| |\
| |                    "required": [| |\
| |                        "location"| |\
| |                    ],| |\
| |                    "type": "object"| |\
| |                }| |\
| |            }| |\
| |        }| |\
| |    ]| |\
| |  }'| |\
| |```| |\
| | | |

<span id="8b944a66"></span>
# 使用说明
<span id="e1e56b26"></span>
## 工作原理

* 多轮对话场景


<span aceTableMode="list" aceTableWidth="8,3"></span>
|流程图 |说明 |
|---|---|
|<img src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHhtbG5zOnhsaW5rPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hsaW5rIiB2ZXJzaW9uPSIxLjEiIHdpZHRoPSI3NDVweCIgaGVpZ2h0PSI0MTVweCIgdmlld0JveD0iLTAuNSAtMC41IDc0NSA0MTUiPjxkZWZzLz48Zz48cmVjdCB4PSIyIiB5PSIyIiB3aWR0aD0iNzQwIiBoZWlnaHQ9IjQxMCIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iMjU4IiB5PSIxMiIgd2lkdGg9IjIzMCIgaGVpZ2h0PSIzOTAiIGZpbGw9IiNmNWY1ZjUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjEyIiB5PSIxMiIgd2lkdGg9IjIzMCIgaGVpZ2h0PSIzOTAiIGZpbGw9IiNmNWY1ZjUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjI2NyIgeT0iMjIiIHdpZHRoPSIyMTAiIGhlaWdodD0iMzcwIiBmaWxsPSJub25lIiBzdHJva2U9IiMwMDAwMDAiIHN0cm9rZS1kYXNoYXJyYXk9IjMgMyIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjUwNiIgeT0iMTIiIHdpZHRoPSIyMzAiIGhlaWdodD0iMzkwIiBmaWxsPSIjZjVmNWY1IiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cmVjdCB4PSI1MTYiIHk9IjIyIiB3aWR0aD0iMjEwIiBoZWlnaHQ9IjM3MCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMDAwMDAwIiBzdHJva2UtZGFzaGFycmF5PSIzIDMiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cmVjdCB4PSIyMiIgeT0iMjIiIHdpZHRoPSIyMTAiIGhlaWdodD0iMzcwIiBmaWxsPSJub25lIiBzdHJva2U9IiMwMDAwMDAiIHN0cm9rZS1kYXNoYXJyYXk9IjMgMyIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjkyIiB5PSI0MSIgd2lkdGg9IjcwIiBoZWlnaHQ9IjIwIiBmaWxsPSJub25lIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiA2OHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDUxcHg7IG1hcmdpbi1sZWZ0OiA5M3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+56ys5LiA6L2u5a+56K+dPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIzMiIgeT0iNzIiIHdpZHRoPSIxOTAiIGhlaWdodD0iNjAiIHJ4PSI2IiByeT0iNiIgZmlsbD0iI2Q1ZThkNCIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iOTIiIHk9Ijg1IiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAxMDBweDsgbWFyZ2luLWxlZnQ6IDkzcHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7pl67popggMTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNDIiIHk9IjkyIiB3aWR0aD0iNDAiIGhlaWdodD0iMjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDM4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMTAycHg7IG1hcmdpbi1sZWZ0OiA0M3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6L6T5YWlPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIzNDEiIHk9IjQxIiB3aWR0aD0iNzEiIGhlaWdodD0iMjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDY5cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogNTFweDsgbWFyZ2luLWxlZnQ6IDM0MnB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+56ys5LqM6L2u5a+56K+dPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIyNzYiIHk9IjcyIiB3aWR0aD0iMTkwIiBoZWlnaHQ9IjEzMCIgcng9IjkuMSIgcnk9IjkuMSIgZmlsbD0iI2Q1ZThkNCIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iMzMyIiB5PSI4MiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogOTdweDsgbWFyZ2luLWxlZnQ6IDMzM3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6Zeu6aKYIDE8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjMzMiIgeT0iMTIyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAxMzdweDsgbWFyZ2luLWxlZnQ6IDMzM3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+5Zue562UMTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iMjg2IiB5PSIxMDIiIHdpZHRoPSI0MCIgaGVpZ2h0PSIyMCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMzhweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAxMTJweDsgbWFyZ2luLWxlZnQ6IDI4N3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6L6T5YWlPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI2MDEiIHk9IjQxIiB3aWR0aD0iNjUiIGhlaWdodD0iMjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDYzcHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogNTFweDsgbWFyZ2luLWxlZnQ6IDYwMnB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+56ys5LiJ6L2u5a+56K+dPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI1MjgiIHk9IjcyIiB3aWR0aD0iMTkwIiBoZWlnaHQ9IjIxMCIgcng9IjkuNSIgcnk9IjkuNSIgZmlsbD0iI2Q1ZThkNCIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iNTgzIiB5PSI4MiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogOTdweDsgbWFyZ2luLWxlZnQ6IDU4NHB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6Zeu6aKYIDE8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjU4MyIgeT0iMTIyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAxMzdweDsgbWFyZ2luLWxlZnQ6IDU4NHB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+5Zue562UIDE8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjUzMyIgeT0iMTczIiB3aWR0aD0iNDAiIGhlaWdodD0iMjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDM4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMTgzcHg7IG1hcmdpbi1sZWZ0OiA1MzRweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPui+k+WFpTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNTgzIiB5PSIxNjIiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmZmZmYiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDE3N3B4OyBtYXJnaW4tbGVmdDogNTg0cHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7pl67popggMjwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNTgzIiB5PSIyMDIiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmZmZmYiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDIxN3B4OyBtYXJnaW4tbGVmdDogNTg0cHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7lm57nrZQgMjwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iMzIiIHk9IjE0MiIgd2lkdGg9IjE5MCIgaGVpZ2h0PSI5MCIgcng9IjEyLjYiIHJ5PSIxMi42IiBmaWxsPSIjZDRlMWY1IiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cmVjdCB4PSI5MiIgeT0iMTUyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmMmNjIiBzdHJva2U9IiMwMDAwMDAiIHN0cm9rZS1kYXNoYXJyYXk9IjMgMyIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDE2N3B4OyBtYXJnaW4tbGVmdDogOTNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuaAnee7tOmTviAxPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI5MiIgeT0iMTkyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAyMDdweDsgbWFyZ2luLWxlZnQ6IDkzcHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7lm57nrZQgMTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNDIiIHk9IjE3MiIgd2lkdGg9IjQwIiBoZWlnaHQ9IjIwIiBmaWxsPSJub25lIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAzOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDE4MnB4OyBtYXJnaW4tbGVmdDogNDNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPui+k+WHujwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iMjc2IiB5PSIyMTMiIHdpZHRoPSIxOTAiIGhlaWdodD0iOTAiIHJ4PSIxMy41IiByeT0iMTMuNSIgZmlsbD0iI2Q0ZTFmNSIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iMzMyIiB5PSIyMjMiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmYyY2MiIHN0cm9rZT0iIzAwMDAwMCIgc3Ryb2tlLWRhc2hhcnJheT0iMyAzIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMjM4cHg7IG1hcmdpbi1sZWZ0OiAzMzNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuaAnee7tOmTviAyPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIzMzIiIHk9IjI2MyIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMjc4cHg7IG1hcmdpbi1sZWZ0OiAzMzNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuWbnuetlDI8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjI4NSIgeT0iMjQzIiB3aWR0aD0iNDAiIGhlaWdodD0iMjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDM4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMjUzcHg7IG1hcmdpbi1sZWZ0OiAyODZweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPui+k+WHujwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNTI4IiB5PSIyOTIiIHdpZHRoPSIxOTAiIGhlaWdodD0iOTAiIHJ4PSIxMy41IiByeT0iMTMuNSIgZmlsbD0iI2Q0ZTFmNSIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iNTgwIiB5PSIzMDIiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmYyY2MiIHN0cm9rZT0iIzAwMDAwMCIgc3Ryb2tlLWRhc2hhcnJheT0iMyAzIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMzE3cHg7IG1hcmdpbi1sZWZ0OiA1ODFweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuaAnee7tOmTviAzPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI1ODAiIHk9IjM0MiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMzU3cHg7IG1hcmdpbi1sZWZ0OiA1ODFweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuWbnuetlCAzPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI1NDEiIHk9IjMyMiIgd2lkdGg9IjQwIiBoZWlnaHQ9IjIwIiBmaWxsPSJub25lIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAzOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDMzMnB4OyBtYXJnaW4tbGVmdDogNTQycHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7ovpPlh7o8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjU4MyIgeT0iMjQzIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAyNThweDsgbWFyZ2luLWxlZnQ6IDU4NHB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6Zeu6aKYIDM8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxwYXRoIGQ9Ik0gMjM1IDExMiBMIDI3NS42MyAxMTIiIGZpbGw9Im5vbmUiIHN0cm9rZT0iIzAwMDAwMCIgc3Ryb2tlLW1pdGVybGltaXQ9IjEwIiBwb2ludGVyLWV2ZW50cz0ic3Ryb2tlIi8+PGVsbGlwc2UgY3g9IjIzMiIgY3k9IjExMiIgcng9IjMiIHJ5PSIzIiBmaWxsPSIjMDAwMDAwIiBzdHJva2U9IiMwMDAwMDAiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cGF0aCBkPSJNIDI4MC44OCAxMTIgTCAyNzMuODggMTE1LjUgTCAyNzUuNjMgMTEyIEwgMjczLjg4IDEwOC41IFoiIGZpbGw9IiMwMDAwMDAiIHN0cm9rZT0iIzAwMDAwMCIgc3Ryb2tlLW1pdGVybGltaXQ9IjEwIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHBhdGggZD0iTSA0ODMgMTEyIEwgNTI1LjYzIDExMiIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMDAwMDAwIiBzdHJva2UtbWl0ZXJsaW1pdD0iMTAiIHBvaW50ZXItZXZlbnRzPSJzdHJva2UiLz48ZWxsaXBzZSBjeD0iNDgwIiBjeT0iMTEyIiByeD0iMyIgcnk9IjMiIGZpbGw9IiMwMDAwMDAiIHN0cm9rZT0iIzAwMDAwMCIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxwYXRoIGQ9Ik0gNTMwLjg4IDExMiBMIDUyMy44OCAxMTUuNSBMIDUyNS42MyAxMTIgTCA1MjMuODggMTA4LjUgWiIgZmlsbD0iIzAwMDAwMCIgc3Ryb2tlPSIjMDAwMDAwIiBzdHJva2UtbWl0ZXJsaW1pdD0iMTAiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cmVjdCB4PSIzMzIiIHk9IjE2MyIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMTc4cHg7IG1hcmdpbi1sZWZ0OiAzMzNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPumXrumimDI8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjwvZz48L3N2Zz4=" /> |* 在每一轮对话过程中，深度思考模型会输出思维链内容（COT）和最终回答（Answer）。|\
| |* 在下一轮对话中，之前输出的思维链内容不会被拼接到上下文中。|\
| |> 思维链内容展现的是模型处理问题的过程，包括将问题拆分为多个问题进行处理，生成多种回复综合得出更好回答等过程。 |


* 工具调用场景（doubao\-seed\-1.8 及后续模型）

工具调用场景中开启深度思考后，为给出更详尽准确的回答，将不会直接丢弃思维链内容，历史轮次的思维链内容按需（模型自主判断）参与推理。在整个请求过程中，用户回传完整上下文即可，由服务端自行判断，是否保留思维链内容。未输入给模型的思维链内容，不会计算 token 用量。代码示例参见[工具调用](/docs/82379/1449737#3e8661f7)。

<span aceTableMode="list" aceTableWidth="8,3"></span>
|流程图 |说明 |
|---|---|
|<img src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHhtbG5zOnhsaW5rPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hsaW5rIiB2ZXJzaW9uPSIxLjEiIHdpZHRoPSI3NzVweCIgaGVpZ2h0PSI0OTVweCIgdmlld0JveD0iLTAuNSAtMC41IDc3NSA0OTUiPjxkZWZzLz48Zz48cmVjdCB4PSIyIiB5PSIyIiB3aWR0aD0iNzcwIiBoZWlnaHQ9IjQ5MCIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iMTIiIHk9IjEzIiB3aWR0aD0iNDgwIiBoZWlnaHQ9IjQ2OSIgZmlsbD0iI2Y1ZjVmNSIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGZsZXgtc3RhcnQ7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDQ3OHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDIwcHg7IG1hcmdpbi1sZWZ0OiAxM3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+5Zue562U6Zeu6aKYIDE8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjI2MiIgeT0iNTIiIHdpZHRoPSIyMjAiIGhlaWdodD0iNDIwIiBmaWxsPSJub25lIiBzdHJva2U9IiM3ZWE2ZTAiIHN0cm9rZS1kYXNoYXJyYXk9IjMgMyIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjMyNiIgeT0iMTg3IiB3aWR0aD0iMTMwIiBoZWlnaHQ9IjQwIiBmaWxsPSJub25lIiBzdHJva2U9IiNmZjk5OTkiIHN0cm9rZS1kYXNoYXJyYXk9IjMgMyIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjUyMiIgeT0iMTMiIHdpZHRoPSIyNDAiIGhlaWdodD0iNDY5IiBmaWxsPSIjZjVmNWY1IiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgZmxleC1zdGFydDsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMjM4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMjBweDsgbWFyZ2luLWxlZnQ6IDUyM3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+5Zue562U6Zeu6aKYIDI8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjUzMiIgeT0iNTIiIHdpZHRoPSIyMjAiIGhlaWdodD0iNDIwIiBmaWxsPSJub25lIiBzdHJva2U9IiM3ZWE2ZTAiIHN0cm9rZS1kYXNoYXJyYXk9IjMgMyIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjIyIiB5PSI1MiIgd2lkdGg9IjIxMCIgaGVpZ2h0PSI0MjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0iIzAwMDAwMCIgc3Ryb2tlLWRhc2hhcnJheT0iMyAzIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iOTIiIHk9IjcxIiB3aWR0aD0iNjAiIGhlaWdodD0iMjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDU4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogODFweDsgbWFyZ2luLWxlZnQ6IDkzcHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7or7fmsYIgMS4xPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIzMiIgeT0iMTAyIiB3aWR0aD0iMTkwIiBoZWlnaHQ9IjkwIiByeD0iOSIgcnk9IjkiIGZpbGw9IiNkNWU4ZDQiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjkyIiB5PSIxMTIiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmZmZmYiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDEyN3B4OyBtYXJnaW4tbGVmdDogOTNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuW3peWFt+WIl+ihqDwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iOTIiIHk9IjE1MiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMTY3cHg7IG1hcmdpbi1sZWZ0OiA5M3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6Zeu6aKYMTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNDIiIHk9IjEzMiIgd2lkdGg9IjQwIiBoZWlnaHQ9IjIwIiBmaWxsPSJub25lIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAzOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDE0MnB4OyBtYXJnaW4tbGVmdDogNDNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPui+k+WFpTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iMzQxIiB5PSI3MSIgd2lkdGg9IjYwIiBoZWlnaHQ9IjIwIiBmaWxsPSJub25lIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiA1OHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDgxcHg7IG1hcmdpbi1sZWZ0OiAzNDJweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuivt+axgiAxLjI8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjI3NiIgeT0iMTAyIiB3aWR0aD0iMTkwIiBoZWlnaHQ9IjIxMCIgcng9IjEzLjMiIHJ5PSIxMy4zIiBmaWxsPSIjZDVlOGQ0IiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cmVjdCB4PSIzMzIiIHk9IjExMiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMTI3cHg7IG1hcmdpbi1sZWZ0OiAzMzNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuW3peWFt+WIl+ihqDwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iMzMyIiB5PSIxNTIiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmZmZmYiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDE2N3B4OyBtYXJnaW4tbGVmdDogMzMzcHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7pl67popgxPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIyODUiIHk9IjE5MiIgd2lkdGg9IjQwIiBoZWlnaHQ9IjIwIiBmaWxsPSJub25lIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAzOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDIwMnB4OyBtYXJnaW4tbGVmdDogMjg2cHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7ovpPlhaU8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjMzMiIgeT0iMTkyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmMmNjIiBzdHJva2U9IiMwMDAwMDAiIHN0cm9rZS1kYXNoYXJyYXk9IjMgMyIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDIwN3B4OyBtYXJnaW4tbGVmdDogMzMzcHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7mgJ3nu7Tpk74gMS4xPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIzMzIiIHk9IjIzMiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMjQ3cHg7IG1hcmdpbi1sZWZ0OiAzMzNweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuW+heiwg+eUqOW3peWFtyAxLjE8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjMzMiIgeT0iMjcyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAyODdweDsgbWFyZ2luLWxlZnQ6IDMzM3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+5bel5YW36LCD55So57uT5p6cIDEuMTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNjE3IiB5PSI3MSIgd2lkdGg9IjYwIiBoZWlnaHQ9IjIwIiBmaWxsPSJub25lIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiA1OHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDgxcHg7IG1hcmdpbi1sZWZ0OiA2MThweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuivt+axgiAyLjE8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjU1MiIgeT0iMTAyIiB3aWR0aD0iMTkwIiBoZWlnaHQ9IjI1MCIgcng9IjkuNSIgcnk9IjkuNSIgZmlsbD0iI2Q1ZThkNCIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHJlY3QgeD0iNjA3IiB5PSIxMTIiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmZmZmYiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDEyN3B4OyBtYXJnaW4tbGVmdDogNjA4cHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7lt6XlhbfliJfooag8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjYwNyIgeT0iMTUyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAxNjdweDsgbWFyZ2luLWxlZnQ6IDYwOHB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6Zeu6aKYMTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNTU3IiB5PSIyMDMiIHdpZHRoPSI0MCIgaGVpZ2h0PSIyMCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMzhweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAyMTNweDsgbWFyZ2luLWxlZnQ6IDU1OHB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6L6T5YWlPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI2MDciIHk9IjE5MiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMjA3cHg7IG1hcmdpbi1sZWZ0OiA2MDhweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPuW+heiwg+eUqOW3peWFtyAxLjE8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjYwNyIgeT0iMjMyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAyNDdweDsgbWFyZ2luLWxlZnQ6IDYwOHB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+5bel5YW36LCD55So57uT5p6cIDEuMTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iMzIiIHk9IjIwMiIgd2lkdGg9IjE5MCIgaGVpZ2h0PSI5MCIgcng9IjEyLjYiIHJ5PSIxMi42IiBmaWxsPSIjZDRlMWY1IiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cmVjdCB4PSI5MiIgeT0iMjEyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmMmNjIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAyMjdweDsgbWFyZ2luLWxlZnQ6IDkzcHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7mgJ3nu7Tpk74gMS4xPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI5MiIgeT0iMjUyIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmZmZmIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAyNjdweDsgbWFyZ2luLWxlZnQ6IDkzcHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7lvoXosIPnlKjlt6XlhbcgMS4xPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI0MiIgeT0iMjMyIiB3aWR0aD0iNDAiIGhlaWdodD0iMjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDM4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMjQycHg7IG1hcmdpbi1sZWZ0OiA0M3B4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+6L6T5Ye6PC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIyNzciIHk9IjMyMiIgd2lkdGg9IjE5MCIgaGVpZ2h0PSI5MCIgcng9IjEzLjUiIHJ5PSIxMy41IiBmaWxsPSIjZDRlMWY1IiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cmVjdCB4PSIzMzMiIHk9IjMzMiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZjJjYyIgc3Ryb2tlPSIjMDAwMDAwIiBzdHJva2UtZGFzaGFycmF5PSIzIDMiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAzNDdweDsgbWFyZ2luLWxlZnQ6IDMzNHB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+5oCd57u06ZO+IDEuMjwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iMzMzIiB5PSIzNzIiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmZmZmYiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDM4N3B4OyBtYXJnaW4tbGVmdDogMzM0cHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7lm57nrZQxPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSIyODYiIHk9IjM1MiIgd2lkdGg9IjQwIiBoZWlnaHQ9IjIwIiBmaWxsPSJub25lIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAzOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDM2MnB4OyBtYXJnaW4tbGVmdDogMjg3cHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7ovpPlh7o8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjU1MiIgeT0iMzYxIiB3aWR0aD0iMTkwIiBoZWlnaHQ9IjkwIiByeD0iMTMuNSIgcnk9IjEzLjUiIGZpbGw9IiNkNGUxZjUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxyZWN0IHg9IjYwNCIgeT0iMzcxIiB3aWR0aD0iMTIwIiBoZWlnaHQ9IjMwIiByeD0iNC41IiByeT0iNC41IiBmaWxsPSIjZmZmMmNjIiBzdHJva2U9Im5vbmUiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48ZyB0cmFuc2Zvcm09InRyYW5zbGF0ZSgtMC41IC0wLjUpIj48Zm9yZWlnbk9iamVjdCBzdHlsZT0ib3ZlcmZsb3c6IHZpc2libGU7IHRleHQtYWxpZ246IGxlZnQ7IiBwb2ludGVyLWV2ZW50cz0ibm9uZSIgd2lkdGg9IjEwMCUiIGhlaWdodD0iMTAwJSI+PGRpdiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMTk5OS94aHRtbCIgc3R5bGU9ImRpc3BsYXk6IGZsZXg7IGFsaWduLWl0ZW1zOiB1bnNhZmUgY2VudGVyOyBqdXN0aWZ5LWNvbnRlbnQ6IHVuc2FmZSBjZW50ZXI7IHdpZHRoOiAxMThweDsgaGVpZ2h0OiAxcHg7IHBhZGRpbmctdG9wOiAzODZweDsgbWFyZ2luLWxlZnQ6IDYwNXB4OyI+PGRpdiBzdHlsZT0iYm94LXNpemluZzogYm9yZGVyLWJveDsgZm9udC1zaXplOiAwOyB0ZXh0LWFsaWduOiBjZW50ZXI7ICI+PGRpdiBzdHlsZT0iZGlzcGxheTogaW5saW5lLWJsb2NrOyBmb250LXNpemU6IDEycHg7IGZvbnQtZmFtaWx5OiBWZXJkYW5hOyBjb2xvcjogIzAwMDAwMDsgbGluZS1oZWlnaHQ6IDEuMjsgcG9pbnRlci1ldmVudHM6IGFsbDsgd2hpdGUtc3BhY2U6IG5vcm1hbDsgd29yZC13cmFwOiBub3JtYWw7ICI+5oCd57u06ZO+IDIuMTwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNjA0IiB5PSI0MTEiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmZmZmYiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDQyNnB4OyBtYXJnaW4tbGVmdDogNjA1cHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj4uLi48L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxyZWN0IHg9IjU1NiIgeT0iMzkxIiB3aWR0aD0iNDAiIGhlaWdodD0iMjAiIGZpbGw9Im5vbmUiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDM4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogNDAxcHg7IG1hcmdpbi1sZWZ0OiA1NTdweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPui+k+WHujwvZGl2PjwvZGl2PjwvZGl2PjwvZm9yZWlnbk9iamVjdD48L2c+PHJlY3QgeD0iNjA3IiB5PSIyNzMiIHdpZHRoPSIxMjAiIGhlaWdodD0iMzAiIHJ4PSI0LjUiIHJ5PSI0LjUiIGZpbGw9IiNmZmZmZmYiIHN0cm9rZT0ibm9uZSIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxnIHRyYW5zZm9ybT0idHJhbnNsYXRlKC0wLjUgLTAuNSkiPjxmb3JlaWduT2JqZWN0IHN0eWxlPSJvdmVyZmxvdzogdmlzaWJsZTsgdGV4dC1hbGlnbjogbGVmdDsiIHBvaW50ZXItZXZlbnRzPSJub25lIiB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIj48ZGl2IHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIiBzdHlsZT0iZGlzcGxheTogZmxleDsgYWxpZ24taXRlbXM6IHVuc2FmZSBjZW50ZXI7IGp1c3RpZnktY29udGVudDogdW5zYWZlIGNlbnRlcjsgd2lkdGg6IDExOHB4OyBoZWlnaHQ6IDFweDsgcGFkZGluZy10b3A6IDI4OHB4OyBtYXJnaW4tbGVmdDogNjA4cHg7Ij48ZGl2IHN0eWxlPSJib3gtc2l6aW5nOiBib3JkZXItYm94OyBmb250LXNpemU6IDA7IHRleHQtYWxpZ246IGNlbnRlcjsgIj48ZGl2IHN0eWxlPSJkaXNwbGF5OiBpbmxpbmUtYmxvY2s7IGZvbnQtc2l6ZTogMTJweDsgZm9udC1mYW1pbHk6IFZlcmRhbmE7IGNvbG9yOiAjMDAwMDAwOyBsaW5lLWhlaWdodDogMS4yOyBwb2ludGVyLWV2ZW50czogYWxsOyB3aGl0ZS1zcGFjZTogbm9ybWFsOyB3b3JkLXdyYXA6IG5vcm1hbDsgIj7lm57nrZQxPC9kaXY+PC9kaXY+PC9kaXY+PC9mb3JlaWduT2JqZWN0PjwvZz48cmVjdCB4PSI2MDciIHk9IjMxMiIgd2lkdGg9IjEyMCIgaGVpZ2h0PSIzMCIgcng9IjQuNSIgcnk9IjQuNSIgZmlsbD0iI2ZmZmZmZiIgc3Ryb2tlPSJub25lIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PGcgdHJhbnNmb3JtPSJ0cmFuc2xhdGUoLTAuNSAtMC41KSI+PGZvcmVpZ25PYmplY3Qgc3R5bGU9Im92ZXJmbG93OiB2aXNpYmxlOyB0ZXh0LWFsaWduOiBsZWZ0OyIgcG9pbnRlci1ldmVudHM9Im5vbmUiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjxkaXYgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkveGh0bWwiIHN0eWxlPSJkaXNwbGF5OiBmbGV4OyBhbGlnbi1pdGVtczogdW5zYWZlIGNlbnRlcjsganVzdGlmeS1jb250ZW50OiB1bnNhZmUgY2VudGVyOyB3aWR0aDogMTE4cHg7IGhlaWdodDogMXB4OyBwYWRkaW5nLXRvcDogMzI3cHg7IG1hcmdpbi1sZWZ0OiA2MDhweDsiPjxkaXYgc3R5bGU9ImJveC1zaXppbmc6IGJvcmRlci1ib3g7IGZvbnQtc2l6ZTogMDsgdGV4dC1hbGlnbjogY2VudGVyOyAiPjxkaXYgc3R5bGU9ImRpc3BsYXk6IGlubGluZS1ibG9jazsgZm9udC1zaXplOiAxMnB4OyBmb250LWZhbWlseTogVmVyZGFuYTsgY29sb3I6ICMwMDAwMDA7IGxpbmUtaGVpZ2h0OiAxLjI7IHBvaW50ZXItZXZlbnRzOiBhbGw7IHdoaXRlLXNwYWNlOiBub3JtYWw7IHdvcmQtd3JhcDogbm9ybWFsOyAiPumXrumimDI8L2Rpdj48L2Rpdj48L2Rpdj48L2ZvcmVpZ25PYmplY3Q+PC9nPjxwYXRoIGQ9Ik0gMjM1IDIwNCBMIDI3NS42MyAyMDQiIGZpbGw9Im5vbmUiIHN0cm9rZT0iIzAwMDAwMCIgc3Ryb2tlLW1pdGVybGltaXQ9IjEwIiBwb2ludGVyLWV2ZW50cz0ic3Ryb2tlIi8+PGVsbGlwc2UgY3g9IjIzMiIgY3k9IjIwNCIgcng9IjMiIHJ5PSIzIiBmaWxsPSIjMDAwMDAwIiBzdHJva2U9IiMwMDAwMDAiIHBvaW50ZXItZXZlbnRzPSJhbGwiLz48cGF0aCBkPSJNIDI4MC44OCAyMDQgTCAyNzMuODggMjA3LjUgTCAyNzUuNjMgMjA0IEwgMjczLjg4IDIwMC41IFoiIGZpbGw9IiMwMDAwMDAiIHN0cm9rZT0iIzAwMDAwMCIgc3Ryb2tlLW1pdGVybGltaXQ9IjEwIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PHBhdGggZD0iTSA0ODUgMjAzLjAxIEwgNTQzLjg5IDIwMy4xOCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjMDAwMDAwIiBzdHJva2UtbWl0ZXJsaW1pdD0iMTAiIHBvaW50ZXItZXZlbnRzPSJzdHJva2UiLz48ZWxsaXBzZSBjeD0iNDgyIiBjeT0iMjAzIiByeD0iMyIgcnk9IjMiIGZpbGw9IiMwMDAwMDAiIHN0cm9rZT0iIzAwMDAwMCIgcG9pbnRlci1ldmVudHM9ImFsbCIvPjxwYXRoIGQ9Ik0gNTQ5LjE0IDIwMy4yIEwgNTQyLjEzIDIwNi42OCBMIDU0My44OSAyMDMuMTggTCA1NDIuMTUgMTk5LjY4IFoiIGZpbGw9IiMwMDAwMDAiIHN0cm9rZT0iIzAwMDAwMCIgc3Ryb2tlLW1pdGVybGltaXQ9IjEwIiBwb2ludGVyLWV2ZW50cz0iYWxsIi8+PC9nPjwvc3ZnPg==" /> |* 回答问题 1 时（请求 1.1 \- 1.2），模型进行多次思考 + 工具调用后给出答案，方舟会输入完整上下文包括思维链内容给模型处理。|\
| |* 开始回答问题2时（请求 2.1），方舟会自行判断并删除之前上下文中的思维链，输入给模型。 |

<span id="bcd721c6"></span>
## 减少请求超时失败
深度思考模型使用思维链输出内容，导致回复篇幅更长、速率更慢，所以极易因超时导致任务失败。尤其在非流式输出模式下，任务未完成时断开连接，未输出内容，又产生 token 用量费用。
可使用流式输出或设置更长超时时间，减少超时失败：

* 使用流式输出（推荐）：通过分块即时返回生成内容，可有效维持连接活性（避免因长时无响应导致的连接中断），是高效且可靠的输出方式（示例代码及说明参见 [流式输出](/docs/82379/1449737#4ad2b076)）。若当前应用使用非流式输出，可改造为：通过流式接口获取内容，实时拼接完整结果后再统一输出，从而显著降低请求超时失败风险。
* 调大超时时间参数：非流式输出场景下，推荐将`timeout`参数设置为30分钟以上，并根据超时触发概率进一步调整超时时间。另需注意网络链路中的 TCP Keep\-Alive 设置（`tcp_keepalive_time`参数），避免因长时间无数据传输导致连接被系统、防火墙、路由器等中断。
> **方舟Go SDK特殊说明**：无论是否使用流式输出，均需将SDK超时参数设置为30分钟以上。

<span id="08906e0e"></span>
## 使用批量推理获得更高吞吐
当您的业务需要处理大量的数据，且对于模型返回及时性要求不高，您可使用批量推理获取最低 10B token/天 的配额以及批量推理的成本降低。批量推理支持任务的方式以及类似 Chat 的接口调用方式，使用批量推理，详细说明请查看[批量推理](/docs/82379/1399517)。
<span id="a33d9cf9"></span>
## 提示词优化建议
深度思考模型会自行分析和拆解问题（思维链），与普通模型相比，提示词侧重点有所不同。

* 提示词除了待解决问题，应该更多补充目标和场景等信息。如使用英语，用Python等语言要求；面向小学生、向领导汇报等阅读对象信息；完成论文写作、完成课题报告、撰写剧本等场景信息；体现我的专业性、获得领导赏识等目标信息。
* 减少或者避免对问题的拆解描述，如分步骤思考、使用示例等，这样会限制住模型的推理逻辑。
* 减少使用系统提示词，所有提示词信息直接通过用户提示词（`role: user`）来提问。

<span id="a1850033"></span>
# 常见问题

* [并发 RPM 或者 TPM 额度明明有剩余为什么提示限流报错？](/docs/82379/1359411#91505308)

默认情况下，方舟 API 会在生成全部内容后，再通过单次 HTTP 响应返回结果。如果输出长内容，等待时间会较长。流式响应模式下，模型会持续发送已生成的数据片段，你可实时看到中间输出过程内容，方便立即开始处理或展示部分结果。
<span id="e9511cf7"></span>
# 效果与优势

<span aceTableMode="list" aceTableWidth="2,1"></span>
|预览 |优势 |
|---|---|
|<video src="https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/0b0ed47ec1b94b20a4f4966aa80130e6~tplv-goo7wpa0wc-image.image" controls></video>|* **改善等待体验**：无需等待完整内容生成完毕，可立即处理过程内容。|\
| |* **实时过程反馈**：多轮交互场景，实时了解任务当前的处理阶段。|\
| |* **更高的容错性**：中途出错，也能获取到已生成内容，避免非流式输出失败无返回的情况。|\
| |* **简化超时管理**：保持客户端与服务端的连接状态，避免复杂任务耗时过长而连接超时。 |

<span id="f1d9aa59"></span>
# 使用说明
<span id="aba1f93c"></span>
## 启用流式
通过配置 **stream** 为 `true`，来启用流式输出。
<span id="9346c907"></span>
## 示例代码

* Chat API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="iotl8pNB2G"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/chat/completions \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer $ARK_API_KEY" \\
  -d '{
    "model": "doubao-seed-2-0-lite-260215",
    "messages": [
        {
            "role": "user",
            "content": "常见的十字花科植物有哪些？"
        }
    ],
    "stream": true
  }'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="CBx8hs7VZL"><RenderMd content={`\`\`\`Python
import os
# Install SDK:  pip install 'volcengine-python-sdk[ark]'
from volcenginesdkarkruntime import Ark 

client = Ark(
    # The base URL for model invocation
    base_url="https://ark.cn-beijing.volces.com/api/v3", 
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.getenv('ARK_API_KEY'), 
)

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        {"role": "user", "content": "常见的十字花科植物有哪些？"},
    ],
    stream=True,
)

# Ensure the connection is closed automatically to prevent connection leaks.
with completion:
    for chunk in completion:
        if chunk.choices[0].delta.content is not None:
            print(chunk.choices[0].delta.content, end="")
\`\`\`

:::tip
\`with completion\`：当 with 代码块内出现异常时，会自动调用对象的 **exit**() 方法进行清理工作。当设置了max_tokens 等中断条件时，可避免socket层数据载满最终程序卡住。

:::`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="tlhpkYHjQh"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model"
    "github.com/volcengine/volcengine-go-sdk/volcengine"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        os.Getenv("ARK_API_KEY"),
        // The base URL for model invocation
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )

    ctx := context.Background()

    fmt.Println("----- standard request -----")
    req := model.CreateChatCompletionRequest{
        // Replace with Model ID
       Model: "doubao-seed-2-0-lite-260215",
        Messages: []*model.ChatCompletionMessage{
            {
                Role: model.ChatMessageRoleUser,
                Content: &model.ChatCompletionMessageContent{
                    StringValue: volcengine.String("常见的十字花科植物有哪些？"),
                },
            },
        },
        Stream: volcengine.Bool(true),
    }

    // 调用 CreateChatCompletionStream 方法，而不要使用非流式调用的 CreateChatCompletion 方法，否则将无法获取流式响应。
    resp, err := client.CreateChatCompletionStream(ctx, req)
    if err != nil {
        fmt.Printf("standard chat error: %\\v", err)
        return
    }

    defer resp.Close()
    for {
        chunk, err := resp.Recv()
        if err != nil {
            fmt.Printf("stream error: %v", err)
            break
        }
        fmt.Print(chunk.Choices[0].Delta.Content)
    }
    fmt.Println()
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="CayfRI1mF4"><RenderMd content={`\`\`\`java
package com.volcengine.ark.runtime;

import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import java.util.ArrayList;
import java.util.List;

public class ChatCompletionsExample {
    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        // The base URL for model invocation
        ArkService service = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();
        final List<ChatMessage> messages = new ArrayList<>();
        final ChatMessage userMessage = ChatMessage.builder().role(ChatMessageRole.USER).content("常见的十字花科植物有哪些？").build();
        messages.add(userMessage);

        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
               .model("doubao-seed-2-0-lite-260215")//Replace with Model ID
               .messages(messages)
               .stream(true)
               .thinking(new ChatCompletionRequest.ChatCompletionRequestThinking("disabled"))
               .build();
        service.streamChatCompletion(chatCompletionRequest)
               .doOnError(Throwable::printStackTrace) // 处理错误
               .blockingForEach(response -> {
                    if (response.getChoices() != null && !response.getChoices().isEmpty()) {
                        String content = String.valueOf(response.getChoices().get(0).getMessage().getContent());
                        if (content != null) {
                            System.out.print(content); // 注意用print而非println，保持内容连续
                        }
                    }
                });
        // shutdown service
        service.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="HoeM55lUy9"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

client = OpenAI(
    # Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
    api_key=os.environ.get("ARK_API_KEY"), 
    base_url="https://ark.cn-beijing.volces.com/api/v3",
    )

completion = client.chat.completions.create(
    # Replace with Model ID
    model = "doubao-seed-2-0-lite-260215",
    messages=[
        {"role": "user", "content": "常见的十字花科植物有哪些？"},
    ],
    stream=True,
)

# Ensure the connection is closed automatically to prevent connection leaks.
with completion: 
    for chunk in completion:
        if chunk.choices[0].delta.content is not None:
            print(chunk.choices[0].delta.content, end="")
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```


* Responses API 示例代码：


```mixin-react
return (<Tabs>
<Tabs.TabPane title="Curl" key="CwDiO3f1yj"><RenderMd content={`\`\`\`Bash
curl https://ark.cn-beijing.volces.com/api/v3/responses \\
  -H "Authorization: Bearer $ARK_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
      "model": "doubao-seed-2-0-lite-260215",
      "input": "常见的十字花科植物有哪些？",
      "stream": true
  }'
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Python" key="vp24VT5lpC"><RenderMd content={`\`\`\`Python
import os
from volcenginesdkarkruntime import Ark
from volcenginesdkarkruntime.types.responses.response_completed_event import ResponseCompletedEvent
from volcenginesdkarkruntime.types.responses.response_reasoning_summary_text_delta_event import ResponseReasoningSummaryTextDeltaEvent
from volcenginesdkarkruntime.types.responses.response_output_item_added_event import ResponseOutputItemAddedEvent
from volcenginesdkarkruntime.types.responses.response_text_delta_event import ResponseTextDeltaEvent
from volcenginesdkarkruntime.types.responses.response_text_done_event import ResponseTextDoneEvent

# Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
api_key = os.getenv('ARK_API_KEY')

client = Ark(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

# Create a request
response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input="常见的十字花科植物有哪些？",
    stream=True
)

for event in response:
    if isinstance(event, ResponseReasoningSummaryTextDeltaEvent):
        print(event.delta, end="")
    if isinstance(event, ResponseOutputItemAddedEvent):
        print("\\noutPutItem " + event.type + " start:")
    if isinstance(event, ResponseTextDeltaEvent):
        print(event.delta,end="")
    if isinstance(event, ResponseTextDoneEvent):
        print("\\noutPutTextDone.")
    if isinstance(event, ResponseCompletedEvent):
        print("Response Completed. Usage = " + event.response.usage.model_dump_json())
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Go" key="Hp8NHRwJ4X"><RenderMd content={`\`\`\`Go
package main

import (
    "context"
    "fmt"
    "os"

    "github.com/volcengine/volcengine-go-sdk/service/arkruntime"
    "github.com/volcengine/volcengine-go-sdk/service/arkruntime/model/responses"
)

func main() {
    client := arkruntime.NewClientWithApiKey(
        // Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
        os.Getenv("ARK_API_KEY"),
        arkruntime.WithBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
    )
    ctx := context.Background()

    resp, err := client.CreateResponsesStream(ctx, &responses.ResponsesRequest{
        Model:    "doubao-seed-2-0-lite-260215",
        Input:    &responses.ResponsesInput{Union: &responses.ResponsesInput_StringValue{StringValue: "常见的十字花科植物有哪些？"}},
    })
    if err != nil {
        fmt.Printf("stream error: %v", err)
        return
    }
    for {
        event, err := resp.Recv()
        if err == io.EOF {
            break
        }
        if err != nil {
            fmt.Printf("stream error: %v", err)
            return
        }
        handleEvent(event)
    }
}
func handleEvent(event *responses.Event) {
    switch event.GetEventType() {
    case responses.EventType_response_reasoning_summary_text_delta.String():
        print(event.GetReasoningText().GetDelta())
    case responses.EventType_response_reasoning_summary_text_done.String(): // aggregated reasoning text
        fmt.Printf("\\nAggregated reasoning text: %s\\n", event.GetReasoningTextDone().GetText())
    case responses.EventType_response_output_text_delta.String():
        print(event.GetText().GetDelta())
    case responses.EventType_response_output_text_done.String(): // aggregated output text
        fmt.Printf("\\nAggregated output text: %s\\n", event.GetTextDone().GetText())
    default:
        return
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Java" key="fRtSWnmkwy"><RenderMd content={`\`\`\`Java
package com.ark.example;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.responses.request.*;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.common.ResponsesThinking;
import com.volcengine.ark.runtime.model.responses.event.functioncall.FunctionCallArgumentsDoneEvent;
import com.volcengine.ark.runtime.model.responses.event.outputitem.OutputItemAddedEvent;
import com.volcengine.ark.runtime.model.responses.event.outputitem.OutputItemDoneEvent;
import com.volcengine.ark.runtime.model.responses.event.outputtext.OutputTextDeltaEvent;
import com.volcengine.ark.runtime.model.responses.event.outputtext.OutputTextDoneEvent;
import com.volcengine.ark.runtime.model.responses.event.reasoningsummary.ReasoningSummaryTextDeltaEvent;
import com.volcengine.ark.runtime.model.responses.event.response.ResponseCompletedEvent;

public class demo {
    public static void main(String[] args) {
        String apiKey = System.getenv("ARK_API_KEY");
        // The base URL for model invocation
        ArkService arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();

        CreateResponsesRequest request = CreateResponsesRequest.builder()
                .model("doubao-seed-2-0-lite-260215")
                .stream(true)
                .input(ResponsesInput.builder().stringValue("常见的十字花科植物有哪些？").build())
                .build();
        arkService.streamResponse(request)
            .doOnError(Throwable::printStackTrace)
            .blockingForEach(event -> {
                if (event instanceof ReasoningSummaryTextDeltaEvent) {
                    System.out.print(((ReasoningSummaryTextDeltaEvent) event).getDelta());
                }
                if (event instanceof OutputItemAddedEvent) {
                    System.out.println("OutputItem " + (((OutputItemAddedEvent) event).getItem().getType()) + " Start: ");
                }
                if (event instanceof OutputTextDeltaEvent) {
                    System.out.print(((OutputTextDeltaEvent) event).getDelta());
                }
                if (event instanceof OutputTextDoneEvent) {
                    System.out.println("OutputText End.");
                }
                if (event instanceof OutputItemDoneEvent) {
                    System.out.println("OutputItem " + ((OutputItemDoneEvent) event).getItem().getType() + " End.");
                }
                if (event instanceof FunctionCallArgumentsDoneEvent) {
                    System.out.println("FunctionCall Arguments: " + ((FunctionCallArgumentsDoneEvent) event).getArguments());
                }
                if (event instanceof ResponseCompletedEvent) {
                    System.out.println("Response Completed. Usage = " + ((ResponseCompletedEvent) event).getResponse().getUsage());
                }
            });
    
        arkService.shutdownExecutor();
    }
}
\`\`\`

`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="OpenAI SDK" key="qtIAfRNPgP"><RenderMd content={`\`\`\`Python
import os
from openai import OpenAI

# Get API Key：https://console.volcengine.com/ark/region:ark+cn-beijing/apikey
api_key = os.getenv('ARK_API_KEY')

client = OpenAI(
    base_url='https://ark.cn-beijing.volces.com/api/v3',
    api_key=api_key,
)

# Create a request
response = client.responses.create(
    model="doubao-seed-2-0-lite-260215",
    input="常见的十字花科植物有哪些？",
    stream=True
)

for event in response:
    if event.type == "response.reasoning_summary_text.delta":
        print(event.delta, end="")
    if event.type == "response.output_item.added":
        print("\\noutPutItem " + event.type + " start:")
    if event.type == "response.output_text.delta":
        print(event.delta,end="")
    if event.type == "response.output_item.done":
        print("\\noutPutTextDone.")
    if event.type == "response.completed":
        print("\\nResponse Completed. Usage = " + event.response.usage.model_dump_json())
\`\`\`

`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="70213630"></span>
## 返回示例
流式响应基于**Server\-Sent Events (SSE)**  协议实现，其核心是服务端通过HTTP长连接持续向客户端推送数据片段。每个数据片段（Chunk）由**字段行**组成。包括模型深度思考内容片段、回复内容片段、工具调用片段等。流式响应结束时，服务端会推送一个特殊片段，通常包含 `data: [DONE]`

```mixin-react
return (<Tabs>
<Tabs.TabPane title="Chat API" key="EoYDyWM6Tm"><RenderMd content={`\`\`\`JSON
data: {"choices":[{"delta":{"content":"","reasoning_content":"\\n","role":"assistant"},"index":0}],"created":1765713048,"id":"021765713047481dd742fe08f96381a9e3cd447cf1b9ac3192379","model":"doubao-seed-1-6-251015","service_tier":"default","object":"chat.completion.chunk","usage":null}
data: {"choices":[{"delta":{"content":"","reasoning_content":"用户","role":"assistant"},"index":0}],"created":1765713048,"id":"021765713047481dd742fe08f96381a9e3cd447cf1b9ac3192379","model":"doubao-seed-1-6-251015","service_tier":"default","object":"chat.completion.chunk","usage":null}
...
data: {"choices":[{"delta":{"content":"","reasoning_content":"。","role":"assistant"},"index":0}],"created":1765713048,"id":"021765713047481dd742fe08f96381a9e3cd447cf1b9ac3192379","model":"doubao-seed-1-6-251015","service_tier":"default","object":"chat.completion.chunk","usage":null}
data: {"choices":[{"delta":{"content":"你","role":"assistant"},"index":0}],"created":1765713048,"id":"021765713047481dd742fe08f96381a9e3cd447cf1b9ac3192379","model":"doubao-seed-1-6-251015","service_tier":"default","object":"chat.completion.chunk","usage":null}
data: {"choices":[{"delta":{"content":"✧","role":"assistant"},"index":0}],"created":1765713048,"id":"021765713047481dd742fe08f96381a9e3cd447cf1b9ac3192379","model":"doubao-seed-1-6-251015","service_tier":"default","object":"chat.completion.chunk","usage":null}
...
data: {"choices":[{"delta":{"content":"","role":"assistant"},"finish_reason":"stop","index":0}],"created":1765713048,"id":"021765713047481dd742fe08f96381a9e3cd447cf1b9ac3192379","model":"doubao-seed-1-6-251015","service_tier":"default","object":"chat.completion.chunk","usage":null}
data: [DONE]
\`\`\`

返回格式说明：(具体字段参见[Chat API](https://www.volcengine.com/docs/82379/1494384))

* \`choices[0].delta.content\`: 模型生成的消息内容。
* \`choices[0].delta.reasoning_content\`: 模型思考内容。
* \`choices[0].finish_reason\`: 模型停止生成 token 的原因。（仅在最后一个chunk中出现）
`}></RenderMd></Tabs.TabPane>
<Tabs.TabPane title="Responses API" key="XUZA2u2rWx"><RenderMd content={`\`\`\`JSON
event: response.created
data: {"type":"response.created","response":{"created_at":1764229579,"id":"resp_021764229578658fe9a0f6cb2cc6c828e7a59adbdb971872aee70","max_output_tokens":32768,"model":"doubao-seed-1-6-251015","object":"response","thinking":{"type":"enabled"},"service_tier":"default","caching":{"type":"disabled"},"store":true,"expire_at":1764488778},"sequence_number":0}

event: response.in_progress
data: {"type":"response.in_progress","response":{"created_at":1764229579,"id":"resp_021764229578658fe9a0f6cb2cc6c828e7a59adbdb971872aee70","max_output_tokens":32768,"model":"doubao-seed-1-6-251015","object":"response","thinking":{"type":"enabled"},"service_tier":"default","caching":{"type":"disabled"},"store":true,"expire_at":1764488778},"sequence_number":1}

event: response.output_item.added
data: {"type":"response.output_item.added","output_index":0,"item":{"id":"rs_02176422957963700000000000000000000ffffac15dd335c9c43","type":"reasoning","status":"in_progress"},"sequence_number":2}

event: response.reasoning_summary_part.added
data: {"type":"response.reasoning_summary_part.added","item_id":"rs_02176422957963700000000000000000000ffffac15dd335c9c43","output_index":0,"summary_index":0,"part":{"type":"summary_text"},"sequence_number":3}

event: response.reasoning_summary_text.delta
data: {"type":"response.reasoning_summary_text.delta","summary_index":0,"delta":"\\n","item_id":"rs_02176422957963700000000000000000000ffffac15dd335c9c43","output_index":0,"sequence_number":4}
...
event: response.completed
data: {"type":"response.completed","response":{"created_at":1768809358,"id":"resp_021768809358289649f4507e5505b181d56acee99f33e5a9f1075","max_output_tokens":32768,"model":"doubao-seed-1-6-251015","object":"response","output":[{"id":"rs_02176880935899200000000000000000000ffffac154346d65c7e","type":"reasoning","summary":[{"type":"summary_text","text":"\\n...。"}],"status":"completed"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"..."}],"status":"completed","id":"msg_02176880937345100000000000000000000ffffac154346bd6748"}],"service_tier":"default","status":"completed","usage":{"input_tokens":42,"output_tokens":846,"total_tokens":888,"input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":408}},"caching":{"type":"disabled"},"store":true,"expire_at":1769068558},"sequence_number":851}

data: [DONE]

\`\`\`

返回示例说明：（具体字段参见[Responses API](https://www.volcengine.com/docs/82379/1569618)）

* event类型\`response.reasoning_summary_text.delta\`，\`event.delta\`为模型思考内容。
* event类型\`response.output_text.delta\`，\`event.delta\`为模型生成的消息内容。
* event类型\`response.completed\`，\`event.response.usage\`为本次请求的 token 用量。
`}></RenderMd></Tabs.TabPane></Tabs>);
```

<span id="e3f9c7c9"></span>
# API 文档

* [Chat API](https://www.volcengine.com/docs/82379/1494384)
* [Responses API](https://www.volcengine.com/docs/82379/1569618)

<span id="6a2d9c11"></span>
# 更多示例

* 函数调用流式输出，请参见 [流式输出](/docs/82379/1262342#ba983529)。










---

## 7. 要求

如果模型支持多模态，只需在编辑页面显示该模型支持多模态类型。并在聊天界面显示支持的模态类型。
如果模型支持思考模式，只需在编辑页面显示该模型支持思考模式。并在聊天界面显示支持的思考模式。
但是编辑页面不要控制是否显示思考模式，由模型属性决定。比如deepseek-v4-pro支持思考模式，只需要在页面显示该模型支持思考模式即可，并在聊天界面显示思考模式按钮，让用户在使用的时候决定是否使用思考模式，其他模型的思考模式也是如此。

页面选择模型时，下拉选中在每个模型后面用带有颜色的文字或者块来标注当前模型的价格，方便用户选择。

页面编辑已有模型的时候，需要隐藏apikey，防止apikey泄露，需要在后端处理

## 自定义接入

在管理后台选择"自定义"接入方式，需要填写：

| 字段 | 说明 | 示例 |
|------|------|------|
| 显示名称 | 前端展示的名称 | My-Custom-Model |
| API 地址 | 兼容 OpenAI 的基础 URL | `https://api.example.com/v1` |
| API Key | 接口密钥 | `sk-xxx` |
| 模型标识 | API 请求中的 model 参数 | `my-model-name` |
| 协议 | 当前仅支持 OpenAI 兼容 | `openai` |

自定义接入默认不支持思考模式。如需支持，需确保目标模型 API 返回 `reasoning_content` 字段。

---

## 思考模式说明

部分模型支持"深度思考"模式，启用后模型会先输出思维链（reasoning_content），再输出最终回答。

- **支持思考的模型**：对话界面会显示"深度思考"开关，默认开启，用户可手动关闭
- **不支持思考的模型**：开关自动隐藏
- 管理后台中思考模式为只读属性，由内置模型定义自动决定，不可手动修改

### 流式响应格式

思考模式开启时，SSE 返回的 `delta` 对象会包含：

```json
{
  "choices": [{
    "delta": {
      "reasoning_content": "思考内容...",
      "content": "最终回答..."
    }
  }]
}
```

`reasoning_content` 为思维链内容，`content` 为最终输出。
