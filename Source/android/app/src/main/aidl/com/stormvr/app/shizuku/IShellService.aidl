package com.stormvr.app.shizuku;

// Servico que roda DENTRO do processo iniciado pelo Shizuku (uid shell/2000 via ADB, ou root). Criado por Shizuku.bindUserService.
interface IShellService {
    // Metodo de encerramento reservado pelo Shizuku (transacao 16777114): obrigatorio no UserService.
    void destroy() = 16777114;

    // Executa argv no processo privilegiado. Retorna [codigoDeSaida, stdout, stderr].
    String[] exec(in String[] argv, long timeoutMs) = 1;

    // Helper do abridor de apps (mesmo protocolo de texto do RootHelper): uma linha entra, uma linha de resposta sai ("" = sem resposta).
    String helper(String line) = 2;
    // Toques/teclas: sem esperar resposta (a ordem das chamadas é preservada).
    oneway void helperSend(String line) = 3;
}
