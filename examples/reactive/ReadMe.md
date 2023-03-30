# Aktuelle Probleme:
- Execution Model von JS erlaubt keinen endlos eventloop, weil dann nie auf die Task Queue zugegriffen wird.
- Mögliche Lösung: Ein Effekt Programm läuft bis alle trails halten, event listener werden über eine API an die Engine übergeben und die Engine übernimmt kontrolle und erhält eine return function zum Effekt Teil des Programms.

## Control Effekt
Idee: Effekt Program wird in eine JS Environment gebetet. Nachdem das gesammt Effekt Programm durchgelaufen ist, übernimmt das JS Environment. Wenn noch Element in der Task Queue sind, läuft Node.js weiter. 
Tritt ein Event ein, soll die Kontrolle an das Effekt Program zurück gegeben werden und das Event wird an alle Trails gebroadcastet. Nachdem diese durchgelaufen sind, wird die Kontrolle wieder an die JS Umgebung gegeben.

Ablauf für einen Trail mit einem Evenhandler:
1. Trail wird gestartet.
2. Trail startet Eventhandler. (Dieser müsste in der Task Queue sein)
3. Effekt Programm terminiert und übergibt an JS mit einer Funktion, die ein Event erwartet, entweder         terminiert, wenn der Trail terminiert oder wieder an die Umgebung abgibt.