on run
	set chosenFiles to choose file with prompt "Select one or more files to convert" with multiple selections allowed
	my convertItems(chosenFiles)
end run

on open droppedItems
	my convertItems(droppedItems)
end open

on convertItems(droppedItems)
	if (count of droppedItems) is 0 then return

	set appBundlePath to POSIX path of (path to me)
	set converterPath to quoted form of (appBundlePath & "Contents/Resources/tbwk-convert")
	set shellArgs to ""

	repeat with droppedItem in droppedItems
		set shellArgs to shellArgs & " " & quoted form of POSIX path of droppedItem
	end repeat

	try
		do shell script converterPath & shellArgs
		display notification "CSV and PDF have been exported next to the source file." with title "TBWK Converter"
	on error errText number errNumber
		display alert "TBWK conversion failed" message errText as critical
	end try
end convertItems
