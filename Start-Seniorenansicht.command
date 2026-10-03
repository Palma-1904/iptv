#!/bin/bash
# Doppelklick: startet die Webapp und öffnet direkt die Seniorenansicht.
exec "$(dirname "$0")/Start-Webapp.command" senioren.html
