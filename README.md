# Ruído Branco

Aplicativo Android nativo, mínimo e totalmente offline para manter um ruído branco contínuo durante o sono do Saulo.

## Por que não existe arquivo de áudio

O aplicativo gera o ruído em tempo real por PCM usando `AudioTrack`. Não há faixa terminando, reinício de MP3, streaming, anúncio, conexão com servidor ou emenda de loop. Enquanto o serviço estiver ativo, novas amostras são produzidas continuamente.

## Recursos

- liga e desliga em um toque;
- volume interno de 5% a 100%;
- reprodução contínua sem ponto de loop;
- funcionamento offline, sem permissão de internet;
- continua tocando com a tela apagada;
- serviço em primeiro plano com notificação persistente;
- bloqueio parcial de CPU para reduzir interrupções durante a noite;
- saída normal de mídia do Android, inclusive Bluetooth A2DP;
- APK de debug gerado automaticamente pelo GitHub Actions.

## Abrir no Android Studio

1. Clone ou baixe este repositório.
2. Abra a pasta raiz no Android Studio.
3. Use JDK 17 e instale o Android SDK 36 quando solicitado.
4. O projeto usa Android Gradle Plugin 9.3.0 e Gradle 9.5.0.
5. Execute o módulo `app` no celular.
6. Pareie o celular com a Alexa como caixa de som Bluetooth e selecione-a como saída de mídia.

Caso o Android Studio solicite a distribuição do Gradle, escolha a versão 9.5.0. Também é possível gerar o APK pela aba **Actions** do GitHub e baixar o artefato `ruido-branco-debug`.

## Uso

O volume do aplicativo e o volume de mídia do celular são multiplicados. Comece baixo, teste a distância da caixa de som e evite deixar o aparelho ou a Alexa próximos demais do berço.

## Arquitetura

- `MainActivity`: tela e comandos;
- `NoiseService`: serviço de reprodução em primeiro plano;
- `NoiseGenerator`: gerador contínuo de amostras de ruído branco suavizado;
- `AudioTrack`: escrita PCM bloqueante com buffer amplo para estabilidade.

## Build por terminal

Com Gradle 9.5.0 instalado:

```bash
gradle :app:assembleDebug
```

O APK será criado em `app/build/outputs/apk/debug/app-debug.apk`.
