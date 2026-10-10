# AXIOM AI — Android 0.2.0

Assistente de IA independente para Android, com motor llama.cpp e modelo Qwen2.5-0.5B-Instruct local em GGUF. **Não usa API paga.** O modelo deve ser baixado pela primeira vez (~398 MB). Depois funciona offline.

## Novidades da versão 0.2

- Chat escuro redesenhado com fonte sans-serif, cabeçalho compacto e respostas separadas por autor.
- Histórico **persistente no SQLite** do próprio app; botão ☰ alterna entre conversas, botão ＋ inicia outra.
- Salva mensagens ao enviar, respostas durante a geração (em intervalos) e ao concluir.
- Cada chat recebe um título automático, com opção de limpar ou excluir.
- Rascunhos por conversa em SharedPreferences, salvos enquanto você digita.
- Migrador do histórico antigo `conversation` da v0.1, **quando a atualização preserva os dados do Android**.
- Backup manual em JSON: ☰ → Opções → Exportar backup; para restaurar, Importar backup.
- Toque e segure uma resposta para copiá-la.
- Preserva o `applicationId=com.viniison.axiom`, caminho do modelo GGUF e configurações já existentes.

## Instalação no celular

1. Abra [Releases](https://github.com/ViniIsOn/AXIOM/releases) e baixe o APK da versão **0.2**.
2. Tente instalar por cima da v0.1, **sem desinstalar primeiro**. Se o Android informar "app não instalado", veja o aviso de assinatura abaixo.
3. Abra o app e confira se o histórico e o modelo estão acessíveis. Se for a primeira instalação, use Modelo → Baixar IA.

### ATENÇÃO: assinatura de APK de teste

A versão v0.1 foi criada com uma chave debug gerada pelo GitHub Actions, que pode **não ser a mesma da v0.2**. Isso impede instalação por cima e NÃO é um problema do código. Neste caso, **não desinstale** se quer manter dados da v0.1: esse app não tinha exportação de histórico. Desinstalar apaga o histórico privado e o GGUF, exigindo novo download.

A partir da v0.2 a automação usa cache do debug.keystore de testes para tentar manter a mesma assinatura nas próximas compilacões. **Cache pode expirar ou ser removido**, portanto não é garantia de atualização. Antes de atualizações futuras, exporte backups de conversa. Para distribuição estável, configurar uma chave de assinatura privada no GitHub Actions Secrets (não publique chave privada no repositório).

O arquivo JSON não inclui os pesos GGUF nem senhas. Guarde-o em lugar privado: contém o texto das conversas.

## Tecnologias

Android nativo/Kotlin/XML, SQLiteOpenHelper, [llama-kotlin-android](https://github.com/CodeShipping/llama-kotlin-android) e [GGUF Qwen2.5-0.5B-Instruct](https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF).

### Limitações

- Modelo pequeno: pode errar ou demorar para responder.
- Sem pesquisa na web ou ferramentas externas nesta versão.
- Histórico completo fica salvo no aparelho, mas **apenas as mensagens recentes entram no contexto enviado ao modelo**, para limitar RAM.
- A importação de backup aceita arquivos JSON de até 4 MB, no máximo 300 conversas e 3 mil mensagens por conversa.
- APK debug de testes, não release pronto para Play Store.

Fonte e créditos no repositório.