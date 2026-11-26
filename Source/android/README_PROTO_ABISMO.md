# Operação Abismo VR — Protótipo 01

Este protótipo adiciona um modo experimental ao aplicativo StormVR existente. O modo é iniciado pelo atalho **Abismo VR** na tela inicial e reutiliza o `VrRenderer` e o rastreamento 3DoF por giroscópio do projeto.

## O que já está incluído

- Cena submarina 3D procedural (sem baixar modelos externos): gradiente de água, fundo arenoso, rochas, algas, corais, peixes e bolhas.
- Renderização estereoscópica lado a lado para uso em um visor passivo tipo VR Box.
- Rastreamento da orientação da cabeça pelo giroscópio já implementado no StormVR.
- Duas válvulas selecionáveis pelo olhar: azul adiciona ar; laranja libera ar.
- Seleção por permanência do olhar por aproximadamente 1,2 segundo. É preciso desviar o olhar para rearmar a válvula.
- Uma cápsula de resgate sobe ou desce conforme o nível de ar. O aro fica verde quando ela atinge a faixa de equilíbrio.
- O modo Abismo não solicita câmera, não inicializa ARCore nem inicia os controles Bluetooth/ligação com PC.

## Como testar no Android Studio

1. Extraia o ZIP do projeto.
2. No Android Studio, use **File > Open** e selecione somente a pasta `Source/android`.
3. Aguarde o Gradle Sync. O projeto precisa baixar dependências Maven na primeira sincronização; use internet.
4. Ative as opções de desenvolvedor e a depuração USB no Android.
5. Conecte o Galaxy A35 por USB, autorize o computador e selecione o aparelho no Android Studio.
6. Use **Build > Build Bundle(s) / APK(s) > Build APK(s)** ou pressione Run para instalar diretamente.
7. Abra o StormVR e toque no atalho **Abismo VR**.
8. Toque em **Iniciar mergulho**. Coloque o telefone na horizontal dentro do VR Box.

O projeto original não inclui os scripts `gradlew`/`gradlew.bat` nem o JAR do Gradle Wrapper; abra pelo Android Studio e deixe a IDE configurar a distribuição Gradle do projeto. O Android SDK 34 e as dependências do projeto devem estar instalados/baixados.

## Controles dentro da cena

- Olhe para a esfera azul à esquerda e mantenha o olhar: adiciona ar à bolsa de flutuação.
- Olhe para a esfera laranja à direita e mantenha o olhar: libera ar.
- Após cada acionamento, olhe para longe das válvulas para poder acioná-las novamente.
- Objetivo: ajustar a bolsa até a cápsula ficar dentro do aro. Quando o aro ficar verde, a cápsula está na posição de equilíbrio simulada.
- Use o botão Voltar do Android para sair do modo VR.

## Modelo físico e limites desta versão

A cena demonstra qualitativamente o Princípio de Arquimedes: aumentar o volume da bolsa de ar aumenta o volume de água deslocado e, portanto, o empuxo. Nesta primeira versão, a posição vertical da cápsula é uma simulação simplificada controlada pelo nível de ar; ainda não é um solucionador físico quantitativo de forças, massa, densidade da água e aceleração. O objetivo deste protótipo é validar a cena, o rastreamento e a interação no telefone/VR Box antes de aprofundar a simulação.

A qualidade do alinhamento entre as duas imagens, o FOV e a distorção depende do VR Box e das configurações de lente do StormVR. As configurações de distorção `k1/k2` salvas pelo usuário são preservadas. Teste primeiro em baixa duração e ajuste a separação/FOV nas configurações do StormVR, se necessário.

## Estado de validação

Esta cópia foi integrada e revisada estaticamente, mas **não foi possível compilar o APK nem testar no Galaxy A35 neste ambiente**, pois não há Android SDK/Gradle instalado aqui. O primeiro teste precisa ser feito no Android Studio e no aparelho real. Se ocorrer erro de compilação ou renderização, envie o texto do erro ou uma captura de tela para corrigirmos em cima dele.

## Arquivos principais alterados

- `app/src/main/java/com/stormvr/app/MainActivity.kt` — atalho Abismo VR.
- `app/src/main/java/com/stormvr/app/vrroom/VrRoomActivity.kt` — tela de instruções e inicialização isolada do modo Abismo.
- `app/src/main/java/com/stormvr/app/vrroom/VrRenderer.kt` — cenário submarino, válvulas por olhar e simulação da flutuabilidade.
- `app/src/main/java/com/stormvr/app/vrroom/VrState.kt` — flag do modo Abismo.

O restante do StormVR foi mantido na cópia do projeto. A licença original GPL-3.0 e seus avisos permanecem no projeto.
