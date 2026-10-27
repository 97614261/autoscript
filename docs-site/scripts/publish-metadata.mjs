import { parseArgs } from 'node:util'
import { basename, dirname, join, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { describeDownload, sha256 } from './prepare-download.mjs'

const { values } = parseArgs({ options: { apk: { type: 'string' }, description: { type: 'string' }, candidate: { type: 'boolean', default: false }, descriptionFile: { type: 'string' } } })
if (!values.apk || (values.description !== undefined && values.descriptionFile !== undefined)) throw new Error('One APK and one optional description are required')
const apk = resolve(values.apk)
const description = values.descriptionFile ? readFileSync(values.descriptionFile, 'utf8') : values.description
const release = describeDownload(apk, join(dirname(apk), 'output-metadata.json'), sha256(apk), description, values)
release.description ??= '智构开发测试构建，升级前请备份重要项目。'
if (basename(apk) !== 'studio-android-debug.apk' && basename(apk) !== 'studio-android-release.apk') throw new Error('Expected a Studio build artifact')
process.stdout.write(JSON.stringify(release).replace(/[^\x00-\x7f]/g, character => '\\u' + character.charCodeAt(0).toString(16).padStart(4, '0')))
