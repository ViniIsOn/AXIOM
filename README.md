# AXIOM AI — Android 0.3.0

App Android experimental com inferência local real via llama.cpp e sem cobrança de API. **Conserva dados do app v0.2 ao instalar por cima com assinatura compatível.** Não desinstale a versão anterior.

## Funções

- **Modelos**: Qwen2.5-0.5B Instruct Q4_K_M (398 MB, leve e já instalado em v0.2); opção de baixar **Qwen2.5-1.5B Instruct Q4_K_M** (~986 MB, maior, normalmente mais capaz). O Qwen1.5B pode consumir mais de 2 GB de RAM e rodar lentamente. Ambos podem errar.
- **Esforço Alto**: gera um primeiro rascunho e revisa a resposta em uma segunda inferência. É mais lento; não garante precisão.
- **Anexos**: "+" antes da caixa de texto. Textos: txt, md, csv, json, xml e código em texto (primeiros 12 KB). PDF: OCR apenas da primeira página. Fotos: classificação de imagem + OCR local com ML Kit. O modelo GGUF atual é textual: **não recebe nem entende diretamente pixels das fotos, áudio ou páginas inteiras**. Máximo 3 anexos por mensagem.
- **Ditado 🎙**: usa serviço de reconhecimento de voz presente no Android. Pode exigir rede e serviço/idioma compatível; nenhuma gravação é armazenada pela AXIOM.
- **Pesquisa Wikipédia**: habilite plugin ⌘ → Pesquisa Wikipédia; depois alterne Wiki OFF/ON. Mostra resumos e URLs de fontes. Só a busca exige internet. O assistente não tem acesso geral à web.
- **Pesquisa ↗**: abre pesquisa do Google no navegador externo; não lê as páginas automaticamente.
- **Plugins ⌘**: Wikipédia, Calculadora local (`/calc 12*(3+2)`) e navegador; liga/desliga cada um. Interface inicial, sem instalação de plugins de terceiros.
- Histórico SQLite, chats separados, migração v0.1→v0.2, backup JSON, rascunhos e cópia de respostas são preservados.

## Instalar no Moto G24 Power

1. Abra [Releases](https://github.com/ViniIsOn/AXIOM/releases) e baixe `app-debug.apk` da versão v0.3.
2. **Instale por cima da versão existente; não desinstale.** O `applicationId` continua `com.viniison.axiom`.
3. Se o Android recusar por diferença de assinatura, mantenha a v0.2: desinstalar apagará banco de dados e modelo. A assinatura debug fica em cache GitHub Actions para permitir atualizações dentro do mesmo cache; não é solução de longo prazo.
4. Ao abrir, seu modelo existente deve carregar sem baixar novamente; backups continuam em ☰ → Opções.
5. Toque na engrenagem para escolher outro modelo (opcional).
6. Teste separadamente anexos, áudio e plugins; algumas funções dependem de componentes do Android e de conexão ativa.

## Detalhes técnicos

- Kotlin/XML nativo, AndroidX, SQLiteOpenHelper; `applicationId com.viniison.axiom`, targetSdk 35.
- Qwen2.5 1.5B GGUF: [bartowski/Qwen2.5-1.5B-Instruct-GGUF](https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF).
- Reconhecimento de imagens/texto: [Google ML Kit](https://developers.google.com/ml-kit/vision).
- Pesquisa: [Wikipédia MediaWiki API](https://www.mediawiki.org/wiki/API:Main_page).
- Repositório público **não armazena conversas nem chaves de API**. Textos de conversa e extrações de anexos ficam na base SQLite do aparelho; backups exportados devem ser guardados com privacidade.

## Limitações e testes

- APK de debug para teste, não pronto para Play Store.
- Compilação CI não substitui teste no dispositivo real.
- Wiki fornece busca enciclopédica, não uma busca geral de lojas, vagas ou serviços locais.
- Abrir uma foto não habilita reconhecimento completo de cenas: o app fornece rótulos aproximados e texto OCR ao modelo de linguagem.
- Modo alto tem duas passadas de geração. Não é garantia de raciocínio perfeito, e o celular pode esquentar/ficar lento.
