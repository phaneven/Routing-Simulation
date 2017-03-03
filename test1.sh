 #!/bin/bash
xterm -e "bash -c \"java Lsr A 2000 ./test1/configA.txt; exec bash\"" &
xterm -e "bash -c \"java Lsr B 2001 ./test1/configB.txt; exec bash\"" &
xterm -e "bash -c \"java Lsr C 2002 ./test1/configC.txt; exec bash\"" &
xterm -e "bash -c \"java Lsr D 2003 ./test1/configD.txt; exec bash\"" &
xterm -e "bash -c \"java Lsr E 2004 ./test1/configE.txt; exec bash\"" &
xterm -e "bash -c \"java Lsr F 2005 ./test1/configF.txt; exec bash\"" &
