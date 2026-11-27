# Operação Abismo VR — protótipo de cockpit submarino

O modo Abismo reutiliza o renderizador estéreo do StormVR e o giroscópio do aparelho. Esta etapa prioriza ambientação e legibilidade dos comandos.

## Direção visual

A cena é inteiramente vista de dentro de um submarino compacto, sem janela direta para o oceano. O casco fechado tem piso e teto metálicos, escotilha estanque, nervuras estruturais, tubulações de lastro e refrigeração, braçadeiras, cabos, armários laterais, cilindros, bateria, luminária e console frontal.

O console frontal reúne a câmera externa simulada e o sonar. Um painel cartográfico separado fica montado na parede à direita do piloto. Esse mapa mostra curvas batimétricas do fundo, grade de coordenadas, trilha até o destino, posição atual e coordenadas XYZ simuladas.

Há também mostradores analógicos, LEDs de lastro e luzes de estado. As telas e leituras são elementos visuais de protótipo; ainda não representam sensores ou uma missão navegável completa.

A atmosfera é inspirada em jogos de horror submarino de cabine fechada, sem reproduzir personagens, arte, mapas ou interfaces protegidas de *Iron Lung*.

## Controles — seis setas na bancada

O olhar e o giroscópio servem apenas para olhar ao redor. Eles **não acionam** os comandos.

- Na primeira utilização, mostre **as duas mãos juntas** à câmera por um instante. O jogo calibra esquerda/direita antes de aceitar uma mão isolada.
- A bancada tem seis placas retangulares com setas, no estilo da referência enviada:
  - **← / →:** deslocam a posição lateral X;
  - **frente / trás:** alteram a posição Y no mapa;
  - **↑ / ↓:** aumentam ou reduzem a flutuabilidade do lastro e fazem o submarino subir ou descer.
- As placas são pequenas e ficam agrupadas perto do piloto, na parte superior da bancada.
- Para acionar, basta apoiar a palma da mão sobre a placa desejada. Não é necessário pinçar, apontar nem tocar exatamente no centro; a área de detecção é maior que a placa física.
- A placa acende brevemente quando é acionada. Tire a mão da área para rearmá-la antes do próximo toque.

O modo Abismo usa o rastreamento das duas mãos pela câmera. A permissão da câmera é necessária para jogar com os comandos.

## Áudio ambiente

Durante o mergulho, o protótipo reproduz um som procedural de água abafada, com bolhas ocasionais em volume baixo. O áudio pausa quando o app sai de foco e para ao sair do modo VR. Não há gritos, sustos, impactos repentinos ou sons de terror.

## Mapa, coordenadas e pressão

O mapa cartográfico fica na parede à direita do piloto (é preciso virar a cabeça para a direita para observá-lo). Em vez da palavra “ROTA” com uma única linha, ele apresenta curvas batimétricas irregulares para representar o relevo do fundo oceânico, grade, trilha tracejada, marcador da posição atual, destino e coordenadas simuladas. As setas esquerda/direita e frente/trás movem o marcador e atualizam **X/Y**; **Z** acompanha a profundidade. Esses valores são coordenadas internas do protótipo, não GPS nem navegação real no oceano.

Ao ultrapassar o limite de profundidade, a pressão rompe uma seção da tubulação lateral. Bolhas saem do trecho rompido, uma luz vermelha alerta o jogador e o indicador de O2 começa a cair. A taxa de perda aumenta com a profundidade. Quando o O2 chega a zero, a imagem escurece gradualmente durante quatro segundos e o aplicativo encerra a atividade do jogo.

## Estado inicial da missão

O submarino começa próximo à superfície, com a reserva de O2 completa (300 segundos). O indicador de lastro é separado do O2 e começa com uma reserva positiva de ar para manter o veículo próximo da superfície.

## O que ainda não está pronto

1. A missão com começo, objetivo, tarefas e final.
2. A câmera externa com exploração real do ambiente.
3. Navegação horizontal funcional e detecção real de alvos pelo sonar.
4. Sistemas de energia, reparo de casco e objetivos de missão com consequências jogáveis.
5. Teste de legibilidade, conforto, identificação das mãos e desempenho no Galaxy A35 com o VR Box.

A simulação de lastro atual é didática e simplificada. Ela não representa um modelo hidrodinâmico ou de submarino real.

## Mesa de xadrez

A mesa 3D de xadrez fica na **Sala VR**, fora do modo Abismo. É cenário/teste de gestos nesta versão, não um tabuleiro com regras e partidas jogáveis.

## Desempenho e compatibilidade

O modo Abismo usa renderização estéreo leve. O desempenho real depende da iluminação, temperatura e capacidade do aparelho. A compilação Android não substitui o teste físico no Galaxy A35/VR Box.
