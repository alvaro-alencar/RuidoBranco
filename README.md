# Ruído Branco

Aplicativo Android nativo para manter um ruído contínuo durante o sono do bebê. O áudio é gerado no próprio aparelho e não depende de internet, streaming ou arquivo em loop.

## Modos do mesmo aplicativo

O mesmo APK pode assumir dois papéis:

- **Reprodutor**: fica perto da Alexa, gera o ruído e envia o áudio por Bluetooth;
- **Controle remoto**: fica com o responsável e envia apenas comandos ao reprodutor.

Os aparelhos se conectam diretamente pelo Nearby Connections, que combina Bluetooth, BLE e Wi-Fi. O áudio nunca é transmitido entre os celulares. Se o controle sair do alcance ou for desligado, o reprodutor continua tocando normalmente.

## Pareamento

1. Instale exatamente o mesmo APK nos dois celulares.
2. No aparelho perto da Alexa, selecione **Reprodutor**.
3. No celular principal, selecione **Controle**.
4. Confira o código de seis dígitos mostrado nos dois aparelhos.
5. Confirme em ambos.

Depois do primeiro pareamento, os aparelhos guardam uma identidade e um segredo local para reconexões automáticas. Nenhuma conta ou servidor é usado.

## Recursos

- liga e desliga em um toque;
- volume interno de 5% a 100%;
- slider de timbre entre mais grave, neutro e mais agudo;
- reprodução contínua sem ponto de loop;
- geração procedural de áudio com `AudioTrack`;
- funcionamento do áudio sem internet;
- continua tocando com a tela apagada;
- serviço em primeiro plano e bloqueio parcial de CPU;
- saída normal de mídia do Android, inclusive Bluetooth A2DP;
- controle remoto ponto a ponto entre dois celulares;
- confirmação visual do código de pareamento;
- reconexão automática de aparelhos confiáveis;
- estado remoto de reprodução, volume, timbre e bateria;
- logo e ícone vetoriais de uma lua dormindo.

## Abrir no Android Studio

1. Clone ou baixe este repositório.
2. Abra a pasta raiz no Android Studio.
3. Use JDK 17 e instale o Android SDK 36 quando solicitado.
4. O projeto usa Android Gradle Plugin 9.3.0 e Gradle 9.5.0.
5. Execute o módulo `app` nos dois celulares.
6. Pareie o celular reprodutor com a Alexa como caixa de som Bluetooth.

O projeto usa `com.google.android.gms:play-services-nearby:19.3.0` para a comunicação direta entre aparelhos.

## Segurança e privacidade

O primeiro pareamento exige que a mesma sequência numérica seja confirmada nos dois aparelhos. Após essa confirmação, um segredo aleatório é guardado apenas localmente e usado para validar reconexões. Comandos recebidos antes da autenticação são ignorados.

O núcleo do aplicativo não possui cadastro, nuvem ou servidor próprio. O Nearby Connections é fornecido pelo Google Play Services.

## Uso seguro

O volume do aplicativo e o volume de mídia do celular são multiplicados. Comece baixo, teste a distância da caixa de som e evite deixar o aparelho ou a Alexa próximos demais do berço.

O controle “Tom do ruído” redistribui a energia entre frequências graves e agudas. A posição central é neutra, a esquerda produz um som mais profundo e a direita um som mais brilhante.

## Arquitetura

- `MainActivity`: interface única para os dois modos;
- `NoiseService`: reprodução contínua em primeiro plano;
- `NoiseGenerator`: gerador com filtros espectrais ajustáveis;
- `RemoteControlService`: descoberta, pareamento, autenticação e comandos;
- `AudioTrack`: escrita PCM bloqueante com buffer amplo.

## Build por terminal

Com Gradle 9.5.0 instalado:

```bash
gradle :app:assembleDebug
```

O APK será criado em `app/build/outputs/apk/debug/app-debug.apk`.
