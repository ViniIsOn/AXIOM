# AXIOM AI Android 0.4 — experimental

Atualização direta da AXIOM 0.3. Projeto nativo Kotlin/Android com modelo GGUF local, sem mensalidade de API. **Mesmo applicationId** \`com.viniison.axiom\`, mesmas preferências, histórico SQLite e caminho para modelos. Instale sem desinstalar.

## Novidades
- Toque e segure em uma **mensagem sua** para editar o texto salvo no SQLite. Respostas anteriores permanecem, mas **não são recalculadas** automaticamente.
- **Imagens anexadas**: miniatura comprimida em \`filesDir/chat_images\`, exibida na conversa mesmo após fechar o app. A IA ainda processa **OCR e rótulos**; não recebe diretamente pixels.
- **Buscar imagens** (▧): Wikimedia Commons com miniaturas dentro do app e links da origem; ou Google Imagens aberto no navegador. Comando \`/imagens husky siberiano\` abre galeria Commons.
- **Google web** (G) ou \`/google pesquisa\`: pesquisa no navegador. Não são retornados resultados do Google ao modelo automaticamente; isso exigiria API e autorização próprias, respeitando as condições do provedor.
- **GitHub público**: ative em ⌘ → GitHub público, depois envie \`/github ViniIsOn/AXIOM\`. Consulta descrição, linguagens e até três commits com API pública do GitHub. **Sem tokens, sem repositórios privados, sem alterações remotas.**
- Menu ⌘ → Codex / Work: **abre o serviço oficial** com informação honesta, sem fingir integração. O Work na nuvem não herda acesso aos arquivos do seu notebook e a AXIOM não pode controlá-lo automaticamente.
- **Estabilidade do Qwen 1.5B**: contexto 768, batch 32, 2 threads e máximo de 160 tokens; evita carregá-lo automaticamente ao reiniciar; desativa o ciclo duplo do Esforço Alto nele e checa a memória livre. Ainda **não há garantia** de não fechar em celulares com pouca RAM. Se falhar, volte ao 0.5B.

## Uso rápido
1. [Releases AXIOM](https://github.com/ViniIsOn/AXIOM/releases) → APK 0.4 → instalar **por cima**, sem desinstalar.
2. Faça backup das conversas em ☰ → Opções → Exportar.
3. Teste uma imagem, pressione e segure uma mensagem para editar e abra ▧ para busca de imagens.
4. Se a atualização for recusada por assinatura de debug, **não desinstale** a versão antiga: desinstalar pode apagar banco e modelos. O GitHub Actions usa cache de chave debug desde v0.2, mas a continuidade do cache não é garantida.

## Limitações importantes
- A edição preserva respostas já dadas: elas podem ficar desatualizadas.
- O backup JSON exporta textos e referências locais, **não copia os arquivos binários de imagens nem os pesos GGUF**.
- Google é aberto no navegador porque raspagem de resultados não foi implementada e uma API oficial pode exigir credenciais/custos.
- GitHub funciona só em repositórios públicos e está sujeito ao limite de consultas anônimas. Para gravar em repositórios privados, futura implementação deve usar OAuth e confirmação granular, jamais token embutido no APK/repositório.
- Codex/Work são produtos externos: atalhos não são execução de Codex, controle remoto, nem acesso a pastas de notebook.
- O modelo 1.5B pode continuar lento ou consumir mais memória do que o dispositivo suporta. O carregamento no aparelho precisa ser testado.
- PDF: OCR apenas na primeira página; imagens locais: classificação/OCR, não visão completa.


## Correção experimental v0.4.1: travamento ao responder
A execução do Qwen2.5 1.5B foi transferida para um **Service** Android separado (`android:process=":axiom_model"`), com contexto de 512 tokens, batch 16, 2 threads e 112 tokens gerados no máximo. Se o motor nativo falhar, o processo principal normalmente permanece vivo, com histórico SQLite intacto.

O aplicativo não carregará automaticamente o modelo 1.5B após reinício. Ele continua disponível no botão Modelo → Baixar Qwen 1.5B (se já baixado, não baixa novamente). O modo Esforço Alto é desligado ao iniciar o app; recomenda-se manter em Normal, sobretudo no celular com RAM limitada.

A geração grava um sinal de atividade em preferências; após reinicialização inesperada, o app mostra aviso de recuperação. Reenviar mensagem idêntica que ficou sem resposta não adiciona outra cópia ao histórico. **Não é garantido que o Android não feche o aplicativo em situações de falta global de memória**.

Observação: a versão 0.4.1 deve ser instalada por cima da v0.4. Não desinstale, pois isso apaga o modelo e as conversas. Exporte backup em ☰ → Opções → Exportar backup antes de instalar. A build GitHub Actions valida compilação, não simula falta de RAM real no Moto G24 Power.
