# Operação Abismo VR — integração com mãos estabilizadas

O modo Abismo reutiliza o renderizador estéreo do StormVR, o giroscópio e o rastreador MediaPipe/One Euro.

## Controles

- Com as mãos visíveis, aponte com o indicador para a esquerda para selecionar a válvula azul (adicionar ar) ou para a direita para selecionar a válvula laranja (liberar ar).
- Faça uma pinça com polegar e indicador para confirmar a válvula.
- Abra a mão ou pare de apontar para soltar a seleção e permitir outro comando.
- As duas mãos voltam a ser desenhadas na cena. A cor muda conforme o gesto: mão aberta (ciano), apontando (amarelo), punho fechado (coral), pinça (verde) e positivo (lilás); em repouso, esquerda azul-gelo e direita coral.
- Sem câmera ou sem mão detectada, mantenha o olhar sobre a válvula por aproximadamente 1,2 segundo como alternativa.
- A cabeça continua sendo orientada pelo giroscópio; a câmera é usada para rastrear as mãos, não para 6DoF da cabeça.

## Objetivo físico

A cápsula de resgate deve ficar alinhada ao aro. A bolsa maior desloca mais água e aumenta o empuxo; reduza o ar para diminuir a flutuabilidade. O protótipo mantém uma simulação qualitativa, não um solucionador de forças calibrado.

## Mesa de xadrez

A mesa 3D de xadrez fica na **Sala VR** (não dentro do cenário submarino), à frente do jogador, com as 32 peças na posição inicial. Ela é uma peça de cenário/teste de gestos nesta versão, ainda não um tabuleiro com regras e partidas jogáveis.

## Desempenho e compatibilidade

O modo Abismo usa rastreamento de duas mãos a 20 fps e renderização leve para equilibrar a fluidez no celular. A qualidade real depende da iluminação, distância das mãos, temperatura e desempenho do aparelho. Teste no Galaxy A35/VR Box depois de compilar o APK.
