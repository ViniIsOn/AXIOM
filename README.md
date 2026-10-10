# AXIOM AI — Android 📱🤖

**Versão inicial 0.1.0 (experimental)**. Assistente com inferência de IA **no próprio celular**, sem computador, conta ou API paga. O modelo não está embutido no APK: baixe-o no app pela primeira vez.

## Cérebro local

- Motor de inferência: [llama-kotlin-android 0.1.0](https://github.com/CodeShipping/llama-kotlin-android) (usa llama.cpp).
- Modelo padrão: [Qwen2.5-0.5B-Instruct Q4_K_M](https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF), ~398 MB. Licença do modelo base: Apache-2.0.
- Pode importar um arquivo **GGUF** compatível pelo seletor de arquivos Android.
- Histórico salvo no armazenamento privado do app.
- Não precisa de internet para conversar depois do download.
- **Limites:** modelo pequeno com erros possíveis, velocidade condicionada ao celular, contexto de conversa reduzido; ainda não tem pesquisa na web nem ferramentas externas.

## Como obter o APK pelo celular

1. Abra [Actions](https://github.com/ViniIsOn/AXIOM/actions) neste repositório.
2. Escolha o fluxo **AXIOM Android APK** e a execução mais recente com símbolo verde.
3. Abra a seção **Artifacts** e baixe `AXIOM-AI-Android-v0.1`.
4. Extraia o ZIP do GitHub e instale `app-debug.apk` (autorize a instalação para seu gerenciador de arquivos apenas quando o Android solicitar).
5. Abra AXIOM AI, pressione **Baixar IA** e aguarde o download de ~398 MB no Wi-Fi. Depois você pode conversar sem internet.

> O GitHub Actions produz **APK de debug** para testes. Não é um APK assinado para publicar na Play Store. O Android pode emitir um aviso de instalação; instale apenas do seu próprio repositório. Nunca publique chaves privadas ou assinaturas no repositório público.

## Desenvolvedor

- Android Kotlin nativo + layouts XML.
- Compilação por GitHub Actions gratuita dentro das cotas do GitHub para repositórios públicos.
- `applicationId`: `com.viniison.axiom`
- minSdk 26, arm64-v8a.

## Fontes e créditos

- Motor: biblioteca [CodeShipping / llama-kotlin-android](https://github.com/CodeShipping/llama-kotlin-android).
- Pesos: [bartowski/Qwen2.5-0.5B-Instruct-GGUF](https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF).
- AXIOM integra esses componentes em um aplicativo próprio. Não desenvolvemos um novo modelo fundacional do zero.
