# Tela de chamada do Android — 30/10/2026

A interface 2026.10.30.4 prioriza o atendimento quando o RBW One está aberto.
Essa versão da interface não instala componentes Android. O APK 1.0.15,
código 17, publicava CallStyle, mas não declarava USE_FULL_SCREEN_INTENT nem
criava uma tela nativa para o recebimento.

A candidata 1.0.16, código 18, acrescenta uma Activity somente de atendimento,
sem WebView, acesso a documentos ou abertura automática de câmera/microfone.
Sua apresentação usa uma notificação de chamada urgente e a autorização de
tela cheia do Android; não usa sobreposição, acessibilidade ou serviço permanente.

## Apresentação

- Com o aplicativo aberto, o painel web continua sendo a tela de atendimento.
- Com o aparelho bloqueado, o Android pode abrir a tela nativa se as notificações,
  o canal de chamadas e a autorização de tela cheia estiverem habilitados.
- Com outro aplicativo aberto e o aparelho desbloqueado, o Android pode manter
  a notificação destacada em vez de trocar de aplicativo. Tocar no aviso abre
  o atendimento. O APK não contorna essa decisão do sistema.
- No Android 14 ou posterior, a tela principal oferece um botão explícito para
  autorizar a tela de chamadas quando o acesso estiver desabilitado. As
  configurações do sistema só são abertas por toque do usuário.

## Vínculo com a chamada

Somente uma chamada Atender recebida pelo SDK e vinculada à sessão corrente
pode apresentar a tela. A confirmação Ligar de uma ligação iniciada pela própria
pessoa conserva seu fluxo anterior. Cada nonce de chamada é apresentado uma vez;
atualizações, retorno e minimização posterior não devem reapresentar a tela.

A tela consulta owner, nonce e validade sem consumir a chamada. Encerramento,
logout, troca de identidade, navegação, expiração ou destruição do runtime
invalidam a apresentação. O estado vive apenas na memória; reiniciar o processo
não restaura comandos antigos. O prazo do aviso não é renovado por heartbeat.

Enquanto o aparelho está bloqueado, o conteúdo da tela é genérico. Atender ou
Recusar exige toque; quando necessário, o sistema solicita o desbloqueio. Antes
de encaminhar a ação ao documento web atual, os vínculos e o prazo são conferidos
novamente. O frontend ainda valida a chamada SDK inbound/ringing antes de operar.
Uma intenção de apresentação nunca atende ou disca automaticamente.

## Alcance

O recebimento continua dependendo do evento SIP já entregue ao WebView ativo.
Esta entrega não implementa o SDK Linkus Android ou push de telefonia. O FCM
existente permanece destinado às notificações gerais. Portanto, essa alteração
não garante recebimento com o processo suspenso, encerrado ou forçado a parar.

A atualização mantém o pacote br.com.rbwone.web e a assinatura de produção da
linha 1.0.13 ou posterior. A distribuição deve preservar todos os canais e APKs
anteriores, disponibilizar o APK novo e conferir tamanho, SHA-256 e certificado
antes de ativar o manifesto web-v2-latest.json. Não se deve desinstalar o app ou
limpar seus dados como parte da atualização.

## Validação

Os testes de estado verificam vínculo, expiração, apresentação única, ausência
de atendimento automático e validade de ações enquanto aguardam desbloqueio.
Compilação, lint e inspeção do APK são registrados na entrega. Sem emulador ou
aparelho conectado, esses resultados não substituem o teste de apresentação,
áudio e decisões de permissão no celular real.

## Referências

- https://developer.android.com/develop/ui/views/notifications/time-sensitive
- https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi
- https://developer.android.com/reference/android/app/NotificationManager#canUseFullScreenIntent()
- https://help.yeastar.com/en/p-series-linkus-cloud-edition/linkus-sdk-guide/push-notifications-linkus-sdk-for-android.html