# Operação Abismo VR — protótipo de cockpit submarino

O modo Abismo reutiliza o renderizador estéreo do StormVR e o giroscópio do aparelho. Esta etapa prioriza ambientação e legibilidade dos comandos.

## Direção visual

A cena é inteiramente vista de dentro de um submarino compacto, sem janela direta para o oceano. O casco fechado tem piso e teto metálicos, escotilha estanque, nervuras estruturais, tubulações de lastro e refrigeração, braçadeiras, cabos, armários laterais, cilindros, bateria, luminária e console frontal.

O console frontal reúne o sonar e uma janela de observação à esquerda. A janela permanece quase totalmente preta, com ruído muito discreto, porque há pouca luz no fundo do mar. Ao lado das seis setas da bancada há um botão FOTO: apoiar a palma nele aciona uma captura. O instantâneo fica visível por alguns segundos e depois a janela volta a escurecer. A partir de 90 m de profundidade a foto pode revelar um destroço e um baú escondido; em águas rasas aparecem apenas vultos.

Há também mostradores analógicos, LEDs de lastro e luzes de estado. As telas e leituras são elementos visuais de protótipo; ainda não representam sensores ou uma missão navegável completa.

A atmosfera é inspirada em jogos de horror submarino de cabine fechada, sem reproduzir personagens, arte, mapas ou interfaces protegidas de *Iron Lung*.

## Controles — seis setas na bancada

O olhar e o giroscópio servem apenas para olhar ao redor. Eles **não acionam** os comandos.

- Na primeira utilização, mostre **as duas mãos juntas** à câmera por um instante. O jogo calibra esquerda/direita antes de aceitar uma mão isolada.
- A bancada tem seis placas retangulares com setas, no estilo da referência enviada:
  - **← / →:** deslocam a posição lateral X;
  - **frente / trás:** alteram a posição Y no mapa;
  - **↑ / ↓:** alteram a profundidade-alvo em passos de 35 m. O submarino acelera e desacelera até a nova profundidade; o medidor, a pressão e o risco de vazamento acompanham a mudança.
- As placas são pequenas e ficam agrupadas perto do piloto, na parte superior da bancada.
- Para acionar, basta apoiar a palma da mão sobre a placa desejada. Não é necessário pinçar, apontar nem tocar exatamente no centro; a área de detecção é maior que a placa física.
- A placa acende brevemente quando é acionada. Tire a mão da área para rearmá-la antes do próximo toque.
- O botão **FOTO**, à esquerda das setas, usa a mesma ativação por apoio da palma. Cada contato tira uma foto; retire a mão e apoie novamente para fotografar outra vez.

O modo Abismo usa o rastreamento das duas mãos pela câmera. A permissão da câmera é necessária para jogar com os comandos.

## Áudio ambiente

Durante o mergulho, o protótipo reproduz um som procedural de água abafada, com bolhas ocasionais em volume baixo. O áudio pausa quando o app sai de foco e para ao sair do modo VR. Não há gritos, sustos, impactos repentinos ou sons de terror.

## Mapa, coordenadas e pressão

O mapa cartográfico fica na parede à direita do piloto (é preciso virar a cabeça para a direita para observá-lo). Em vez da palavra “ROTA” com uma única linha, ele apresenta curvas batimétricas irregulares para representar o relevo do fundo oceânico, grade, trilha tracejada, marcador da posição atual, destino e coordenadas simuladas. As setas esquerda/direita e frente/trás movem o marcador e atualizam **X/Y**; **Z** acompanha a profundidade. Esses valores são coordenadas internas do protótipo, não GPS nem navegação real no oceano.

Ao atingir 180 m, a pressão rompe uma seção da tubulação lateral. Bolhas saem do trecho rompido, uma luz vermelha alerta o jogador e o indicador de O2 começa a cair. A reserva inicial é de **30 segundos**; o consumo acelera com a profundidade. Se o jogador regressar à zona segura (até 10 m) antes de zerar, o sistema sela o vazamento e recarrega automaticamente os 30 segundos para uma nova descida. Se o O2 chegar a zero, a imagem escurece gradualmente durante quatro segundos e o aplicativo encerra a atividade do jogo.

Ao fotografar a área de busca a partir de 90 m, a janela revela um destroço e um baú. O achado encerra a missão com uma cartela simples: **MISSÃO CONCLUÍDA — TESOURO ENCONTRADO**. O final fica visível na cabine; é uma conclusão curta de protótipo, não uma sequência cinematográfica completa.

## Estado inicial da missão

O submarino começa a **8 m de profundidade**, perto da superfície, com 30 segundos de O2. A profundidade-alvo começa em 8 m e muda em passos de 35 m pelas setas subir/descer. O veículo pode descer até 300 m; em 180 m a pressão rompe uma tubulação e inicia o consumo de O2. Ao retornar a até 10 m antes do esgotamento, o cano é selado e a reserva volta a 30 segundos. O indicador de lastro é separado do O2.

## O que ainda não está pronto

1. Uma sequência final cinematográfica e efeitos sonoros de conclusão.
2. A câmera externa com exploração real do ambiente.
3. Navegação horizontal funcional e detecção real de alvos pelo sonar.
4. Sistemas de energia, reparo de casco e objetivos de missão com consequências jogáveis.
5. Teste de legibilidade, conforto, identificação das mãos e desempenho no Galaxy A35 com o VR Box.

A simulação de lastro atual é didática e simplificada. Ela não representa um modelo hidrodinâmico ou de submarino real.

## Mesa de xadrez

A mesa 3D de xadrez fica na **Sala VR**, fora do modo Abismo. É cenário/teste de gestos nesta versão, não um tabuleiro com regras e partidas jogáveis.

## Desempenho e compatibilidade

O modo Abismo usa renderização estéreo leve. O desempenho real depende da iluminação, temperatura e capacidade do aparelho. A compilação Android não substitui o teste físico no Galaxy A35/VR Box.
